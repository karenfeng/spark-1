/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * This aims to handle a nested column aliasing pattern inside the [[ColumnPruning]] optimizer rule.
 * If:
 * - A [[Project]] or its child references nested fields
 * - Not all of the fields in a nested attribute are used
 * Then:
 * - Substitute the nested field references with alias attributes
 * - Add grandchild [[Project]]s transforming the nested fields to aliases
 *
 * Example 1: Project
 * ------------------
 * Before:
 * +- Project [concat_ws(s#0.a, s#0.b) AS concat_ws(s.a, s.b)#1]
 *   +- GlobalLimit 5
 *     +- LocalLimit 5
 *       +- LocalRelation <empty>, [s#0]
 * After:
 * +- Project [concat_ws(_gen_alias_2#2, _gen_alias_3#3) AS concat_ws(s.a, s.b)#1]
 *   +- GlobalLimit 5
 *     +- LocalLimit 5
 *       +- Project [s#0.a AS _gen_alias_2#2, s#0.b AS _gen_alias_3#3]
 *         +- LocalRelation <empty>, [s#0]
 *
 * Example 2: Project above Filter
 * -------------------------------
 * Before:
 * +- Project [s#0.a AS s.a#1]
 *   +- Filter (length(s#0.b) > 2)
 *     +- GlobalLimit 5
 *       +- LocalLimit 5
 *         +- LocalRelation <empty>, [s#0]
 * After:
 * +- Project [_gen_alias_2#2 AS s.a#1]
 *   +- Filter (length(_gen_alias_3#3) > 2)
 *     +- GlobalLimit 5
 *       +- LocalLimit 5
 *         +- Project [s#0.a AS _gen_alias_2#2, s#0.b AS _gen_alias_3#3]
 *           +- LocalRelation <empty>, [s#0]
 *
 * Example 3: Nested columns in nested columns
 * -------------------------------------------
 * Before:
 * +- Project [s#0.a AS s.a#1, s#0.a.a1 AS s.a.a1#2]
 *   +- GlobalLimit 5
 *     +- LocalLimit 5
 *       +- LocalRelation <empty>, [s#0]
 * After:
 * +- Project [_gen_alias_3#3 AS s.a#1, _gen_alias_3#3.name AS s.a.a1#2]
 *   +- GlobalLimit 5
 *     +- LocalLimit 5
 *       +- Project [s#0.a AS _gen_alias_3#3]
 *         +- LocalRelation <empty>, [s#0]
 *
 * The schema of the datasource relation will be pruned in the [[SchemaPruning]] optimizer rule.
 */
object NestedColumnAliasing {

  def unapply(plan: LogicalPlan): Option[LogicalPlan] = plan match {
    /**
     * This pattern is needed to support [[Filter]] plan cases like
     * [[Project]]->[[Filter]]->listed plan in [[canProjectPushThrough]] (e.g., [[Window]]).
     * The reason why we don't simply add [[Filter]] in [[canProjectPushThrough]] is that
     * the optimizer can hit an infinite loop during the [[PushDownPredicates]] rule.
     */
    case Project(projectList, Filter(condition, child)) if
        SQLConf.get.nestedSchemaPruningEnabled && canProjectPushThrough(child) =>
      rewritePlanIfSubsetFieldsUsed(
        plan, projectList ++ Seq(condition) ++ child.expressions, child.producedAttributes.toSeq)

    case Project(projectList, child) if
        SQLConf.get.nestedSchemaPruningEnabled && canProjectPushThrough(child) =>
      rewritePlanIfSubsetFieldsUsed(
        plan, projectList ++ child.expressions, child.producedAttributes.toSeq)

    case p if SQLConf.get.nestedSchemaPruningEnabled && canPruneOn(p) =>
      rewritePlanIfSubsetFieldsUsed(
        plan, p.expressions, p.producedAttributes.toSeq)

    case _ => None
  }

  /**
   * Rewrites a plan with aliases if only a subset of the nested fields are used.
   */
  def rewritePlanIfSubsetFieldsUsed(
    plan: LogicalPlan,
    exprList: Seq[Expression],
    exclusiveAttrs: Seq[Attribute]): Option[LogicalPlan] = {
    val attrToExtractValues = getAttributeToExtractValues(exprList, exclusiveAttrs)
    if (attrToExtractValues.isEmpty) {
      None
    } else {
      Some(rewritePlanWithAliases(plan, attrToExtractValues))
    }
  }

  /**
   * Replace nested columns to prune unused nested columns later.
   */
  def rewritePlanWithAliases(
      plan: LogicalPlan,
      attributeToExtractValues: Map[Attribute, Seq[ExtractValue]]): LogicalPlan = {
      // Each expression can contain multiple nested fields.
      // Note that we keep the original names to deliver to parquet in a case-sensitive way.
      // A new alias is created for each nested field.
      val nestedFieldToAlias = attributeToExtractValues.flatMap { case (_, nestedFields) =>
        nestedFields.map { f =>
          val exprId = NamedExpression.newExprId
          f -> Alias(f, s"_gen_alias_${exprId.id}")(exprId, Seq.empty, None)
        }
      }

      // A reference attribute can have multiple aliases for nested fields.
      val attrToAliases = attributeToExtractValues.map { case (attr, nestedFields) =>
        attr.exprId -> nestedFields.map(nestedFieldToAlias)
      }

      plan match {
        case Project(projectList, child) =>
          Project(
            getNewProjectList(projectList, nestedFieldToAlias),
            replaceWithAliases(child, nestedFieldToAlias, attrToAliases))

        // The operators reaching here are already guarded by [[canPruneOn]].
        case other =>
          replaceWithAliases(other, nestedFieldToAlias, attrToAliases)
      }
  }

  /**
   * Replace the [[ExtractValue]]s in a project list with aliased attributes.
   */
  def getNewProjectList(
    projectList: Seq[NamedExpression],
    nestedFieldToAlias: Map[ExtractValue, Alias]): Seq[NamedExpression] = {
    projectList.map(_.transform {
      case f: ExtractValue if nestedFieldToAlias.contains(f) =>
        nestedFieldToAlias(f).toAttribute
    }.asInstanceOf[NamedExpression])
  }

  /**
   * Replace the grandchildren of a plan with [[Project]]s of the nested fields as aliases,
   * and replace the [[ExtractValue]] expressions with aliased attributes.
   */
  def replaceWithAliases(
      plan: LogicalPlan,
      nestedFieldToAlias: Map[ExtractValue, Alias],
      attrToAliases: Map[ExprId, Seq[Alias]]): LogicalPlan = {
    plan.withNewChildren(plan.children.map { plan =>
      Project(plan.output.flatMap(a => attrToAliases.getOrElse(a.exprId, Seq(a))), plan)
    }).transformExpressions {
      case f: ExtractValue if nestedFieldToAlias.contains(f) =>
        nestedFieldToAlias(f).toAttribute
    }
  }

  /**
   * Returns true for operators on which we can prune nested columns.
   */
  private def canPruneOn(plan: LogicalPlan) = plan match {
    case _: Aggregate => true
    case _: Expand => true
    case _ => false
  }

  /**
   * Returns true for operators through which project can be pushed.
   */
  private def canProjectPushThrough(plan: LogicalPlan) = plan match {
    case _: GlobalLimit => true
    case _: LocalLimit => true
    case _: Repartition => true
    case _: Sample => true
    case _: RepartitionByExpression => true
    case _: Join => true
    case _: Window => true
    case _: Sort => true
    case _ => false
  }

  /**
   * Check [[SelectedField]] to see which expressions should be listed here.
   */
  private def isSelectedField(e: Expression): Boolean = e match {
    case GetStructField(_: ExtractValue | _: AttributeReference, _, _) => true
    case GetArrayStructFields(_: MapValues |
                              _: MapKeys |
                              _: ExtractValue |
                              _: AttributeReference, _, _, _, _) => true
    case _ => false
  }

  /**
   * Return root references that are individually accessed.
   */
  private def collectAttributeReference(e: Expression): Seq[AttributeReference] = e match {
    case a: AttributeReference => Seq(a)
    case g if isSelectedField(g) => Seq.empty
    case es if es.children.nonEmpty => es.children.flatMap(collectAttributeReference)
    case _ => Seq.empty
  }

  /**
   * Return [[GetStructField]] or [[GetArrayStructFields]] on top of other [[ExtractValue]]s
   * or special expressions.
   */
  private def collectExtractValue(e: Expression): Seq[ExtractValue] = e match {
    case g if isSelectedField(g) => Seq(g.asInstanceOf[ExtractValue])
    case es if es.children.nonEmpty => es.children.flatMap(collectExtractValue)
    case _ => Seq.empty
  }

  /**
   * Creates a map from root [[Attribute]]s to non-redundant nested [[ExtractValue]]s.
   * Nested field accessors of `exclusiveAttrs` are not considered in nested fields aliasing.
   */
  def getAttributeToExtractValues(
      exprList: Seq[Expression],
      exclusiveAttrs: Seq[Attribute]): Map[Attribute, Seq[ExtractValue]] = {

    val nestedFieldReferences = exprList.flatMap(collectExtractValue)
    val otherRootReferences = exprList.flatMap(collectAttributeReference)
    val exclusiveAttrSet = AttributeSet(exclusiveAttrs ++ otherRootReferences)

    // Remove cosmetic variations when we group extractors by their references
    nestedFieldReferences
      .filter(!_.references.subsetOf(exclusiveAttrSet))
      .groupBy(_.references.head.canonicalized.asInstanceOf[Attribute])
      .flatMap { case (attr: Attribute, nestedFields: Seq[ExtractValue]) =>
        // Remove redundant [[ExtractValue]]s if they share the same parent nest field.
        // For example, when `a.b` and `a.b.c` are in project list, we only need to alias `a.b`.
        val dedupNestedFields = nestedFields.filter {
          // See [[collectExtractValue]]: we only need to deal with [[GetArrayStructFields]] and
          // [[GetStructField]]
          case e @ (_: GetStructField | _: GetArrayStructFields) =>
            val child = e.children.head
            nestedFields.forall(f => child.find(_.semanticEquals(f)).isEmpty)
          case _ => true
        }.distinct

        // If all nested fields of `attr` are used, we don't need to introduce new aliases.
        // By default, the [[ColumnPruning]] rule uses `attr` already.
        // Note that we need to remove cosmetic variations first, so we only count a
        // nested field once.
        val numUsedNestedFields = dedupNestedFields.map(_.canonicalized).distinct
          .map { nestedField => totalFieldNum(nestedField.dataType) }.sum
        if (numUsedNestedFields < totalFieldNum(attr.dataType)) {
          Some(attr, dedupNestedFields)
        } else {
          None
        }
      }
  }

  /**
   * Return total number of fields of this type. This is used as a threshold to use nested column
   * pruning. It's okay to underestimate. If the number of reference is bigger than this, the parent
   * reference is used instead of nested field references.
   */
  private def totalFieldNum(dataType: DataType): Int = dataType match {
    case _: AtomicType => 1
    case StructType(fields) => fields.map(f => totalFieldNum(f.dataType)).sum
    case ArrayType(elementType, _) => totalFieldNum(elementType)
    case MapType(keyType, valueType, _) => totalFieldNum(keyType) + totalFieldNum(valueType)
    case _ => 1 // UDT and others
  }
}

/**
 * This prunes unnecessary nested columns from [[Generate]], or [[Project]] -> [[Generate]]
 */
object GeneratorNestedColumnAliasing {
  def unapply(plan: LogicalPlan): Option[LogicalPlan] = plan match {
    // Either `nestedPruningOnExpressions` or `nestedSchemaPruningEnabled` is enabled, we
    // need to prune nested columns through Project and under Generate. The difference is
    // when `nestedSchemaPruningEnabled` is on, nested columns will be pruned further at
    // file format readers if it is supported.
    case Project(projectList, g: Generate) if (SQLConf.get.nestedPruningOnExpressions ||
        SQLConf.get.nestedSchemaPruningEnabled) && canPruneGenerator(g.generator) =>
      // On top on `Generate`, a `Project` that might have nested column accessors.
      // We try to get alias maps for both project list and generator's children expressions.
      NestedColumnAliasing.rewritePlanIfSubsetFieldsUsed(
        plan, projectList ++ g.generator.children, g.qualifiedGeneratorOutput)

    case g: Generate if SQLConf.get.nestedSchemaPruningEnabled &&
        canPruneGenerator(g.generator) =>
      // If any child output is required by higher projection, we cannot prune on it even we
      // only use part of nested column of it. A required child output means it is referred
      // as a whole or partially by higher projection, pruning it here will cause unresolved
      // query plan.
      NestedColumnAliasing.rewritePlanIfSubsetFieldsUsed(
        plan, g.generator.children, g.requiredChildOutput)

    case _ =>
      None
  }

  /**
   * Types of [[Generator]] on which we can prune nested fields.
   */
  def canPruneGenerator(g: Generator): Boolean = g match {
    case _: Explode => true
    case _: Stack => true
    case _: PosExplode => true
    case _: Inline => true
    case _ => false
  }
}
