package net.snowflake.spark.snowflake.pushdowns.querygeneration

import org.apache.spark.sql.catalyst.expressions.{
  Alias,
  Ascending,
  Attribute,
  CaseWhenCodegen,
  Cast,
  Descending,
  Expression,
  If,
  ScalarSubquery,
  SortOrder
}
import org.apache.spark.sql.types._

/** Extractors for everything else. */
private[querygeneration] object MiscExpression {

  /** Used mainly by QueryGeneration.convertExpression. This matches
    * a tuple of (Expression, Seq[Attribute]) representing the expression to
    * be matched and the fields that define the valid fields in the current expression
    * scope, respectively.
    *
    * @param expAttr A pair-tuple representing the expression to be matched and the
    *                attribute fields.
    * @return An option containing the translated SQL, if there is a match, or None if there
    *         is no match.
    */
  def unapply(expAttr: (Expression, Seq[Attribute])): Option[String] = {
    val expr   = expAttr._1
    val fields = expAttr._2

    Option(expr match {
      case Alias(child: Expression, name: String) =>
        block(convertExpression(child, fields), name)
      case CaseWhenCodegen(branches, elseValue) =>
        val cases = "CASE " + branches
            .map(
              b =>
                "WHEN " + convertExpression(b._1, fields) + " THEN " + convertExpression(
                  b._2,
                  fields))
            .mkString(" ")
        if (elseValue.isDefined)
          block(
            cases + " ELSE " + convertExpression(elseValue.get, fields) + " END")
        else block(cases + " END")
      case Cast(child, t) =>
        getCastType(t) match {
          case None =>
            convertExpression(child, fields)
          case Some(cast) =>
            "CAST" + block(convertExpression(child, fields) + " AS " + cast)
        }
      case If(child, trueValue, falseValue) =>
        "IFF" + block(convertExpressions(fields, child, trueValue, falseValue))
      case SortOrder(child, Ascending) =>
        block(convertExpression(child, fields)) + " ASC"
      case SortOrder(child, Descending) =>
        block(convertExpression(child, fields)) + " DESC"

      case ScalarSubquery(subquery, _, _) =>
        block(new QueryBuilder(subquery).query)

      case _ => null
    })
  }

  /** Attempts a best effort conversion from a SparkType
    * to a Snowflake type to be used in a Cast.
    */
  private final def getCastType(t: DataType): Option[String] =
    Option(t match {
      case StringType    => "VARCHAR"
      case BinaryType    => "BINARY"
      case DateType      => "DATE"
      case TimestampType => "TIMESTAMP"
      case d: DecimalType =>
        "DECIMAL(" + d.precision + ", " + d.scale + ")"
      case IntegerType | LongType => "NUMBER"
      case FloatType              => "FLOAT"
      case DoubleType             => "DOUBLE"
      case _                      => null
    })
}
