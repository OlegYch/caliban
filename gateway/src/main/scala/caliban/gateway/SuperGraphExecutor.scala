package caliban.gateway

import caliban.CalibanError.ExecutionError
import caliban.ResponseValue.{ListValue, ObjectValue}
import caliban.Value.NullValue
import caliban.execution._
import caliban.gateway.FetchDataSource.FetchRequest
import caliban.gateway.Resolver.{Extractor, Fetcher}
import caliban.gateway.SubGraph.SubGraphExecutor
import caliban.introspection.adt.{Extend, TypeVisitor, __Directive, __TypeKind}
import caliban.parsing.adt.OperationType
import caliban.schema.Step.NullStep
import caliban.schema.{Operation, RootSchemaBuilder, Types}
import caliban.wrappers.Wrapper
import caliban.wrappers.Wrapper.FieldWrapper
import caliban.{CalibanError, GraphQL, GraphQLResponse, ResponseValue}
import zio.prelude.NonEmptyList
import zio.query.ZQuery
import zio.{Chunk, Trace, URIO}

private case class SuperGraphExecutor[-R](
  private val subGraphs: NonEmptyList[SubGraphExecutor[R]],
  private val transformers: Chunk[TypeVisitor]
) extends GraphQL[R] {
  private val subGraphMap: Map[String, SubGraphExecutor[R]] = subGraphs.map(g => g.name -> g).toMap

  protected val wrappers: List[Wrapper[R]]              = Nil
  protected val additionalDirectives: List[__Directive] = Nil
  protected val features: Set[Feature]                  = Set.empty
  protected val schemaBuilder: RootSchemaBuilder[R]     = {
    val builder = subGraphs.collect {
      case subGraph if subGraph.exposeAtRoot && subGraph.schema.queryType.kind == __TypeKind.OBJECT =>
        val rootTypes = Set(
          subGraph.schema.queryType.name,
          subGraph.schema.mutationType.flatMap(_.name),
          subGraph.schema.subscriptionType.flatMap(_.name)
        ).flatten
        RootSchemaBuilder(
          Some(Operation(subGraph.schema.queryType, NullStep)),
          subGraph.schema.mutationType.map(mutation => Operation(mutation, NullStep)),
          subGraph.schema.subscriptionType.map(subscription => Operation(subscription, NullStep))
        ).visit(
          TypeVisitor.fields.modifyWith((t, field) =>
            if (t.name.exists(rootTypes.contains)) field.copy(extend = Some(Extend(subGraph.name, field.name)))
            else field
          )
        )
    }.reduceLeft(_ |+| _)
    transformers.foldLeft(builder) { case (builder, transformer) => builder.visit(transformer) }
  }

  protected override def resolve[R1 <: R](
    op: Operation[R1],
    fieldWrappers: List[FieldWrapper[R1]],
    isIntrospection: Boolean
  )(req: ExecutionRequest)(implicit trace: Trace): URIO[R1, GraphQLResponse[CalibanError]] =
    if (isIntrospection)
      Executor.executeRequest(req, op.plan, fieldWrappers, QueryExecution.Parallel, features)
    else
      resolveRootField(Resolver.Field(req.field), req.operationType)
        .fold(
          error => GraphQLResponse(NullValue, List(error)),
          result => GraphQLResponse(result, Nil)
        )
        .run

  private def resolveRootField(
    root: Resolver.Field,
    operationType: OperationType
  ): ZQuery[R, ExecutionError, ResponseValue] = {
    val dataSource = FetchDataSource[R]

    def foreach[A, B](resolvers: List[A])(f: A => ZQuery[R, ExecutionError, B]): ZQuery[R, ExecutionError, List[B]] =
      operationType match {
        case OperationType.Query | OperationType.Subscription => ZQuery.foreachBatched(resolvers)(f)
        case OperationType.Mutation                           => ZQuery.foreach(resolvers)(f)
      }

    def resolveField(field: Resolver.Field, parent: ResponseValue): ZQuery[R, ExecutionError, ResponseValue] =
      field.resolver match {
        case extractor: Extractor => resolveExtractor(extractor, field, parent)
        case fetcher: Fetcher     => resolveFetcher(fetcher, field, parent)
      }

    def resolveExtractor(
      extractor: Extractor,
      field: Resolver.Field,
      parent: ResponseValue
    ): ZQuery[R, ExecutionError, ResponseValue] =
      extractor.extract(parent.asObjectValue) match {
        case parent: ObjectValue =>
          foreach(field.fields)(field => resolveField(field, parent).map(field.outputName -> _))
            .map(fields => parent.copy(fields = fields))
        case other               => ZQuery.succeed(other)
      }

    def resolveFetcher(
      fetcher: Fetcher,
      field: Resolver.Field,
      parent: ResponseValue
    ): ZQuery[R, ExecutionError, ResponseValue] =
      subGraphMap.get(fetcher.extend.sourceGraph) match {
        case Some(subGraph) =>
          lazy val parentObject = parent.asObjectValue
          val sourceFieldName   = fetcher.extend.sourceFieldName
          val fields            = getFieldsToFetch(field.fields, fetcher.extend.target.map(Set(_))) ++
            fetcher.extend.additionalFields.map(name =>
              caliban.execution.Field(name, Types.string, None, targets = fetcher.extend.target.map(Set(_)))
            )
          val arguments         = field.arguments ++
            fetcher.extend.argumentMappings.map { case (k, f) => f(parentObject.get(k).toInputValue) }.filterNot {
              case (_, v) => v == NullValue
            }
          val batchEnabled      = fetcher.extend.filterBatchResults.isDefined
          val fetch             = FetchRequest(subGraph, sourceFieldName, operationType, fields, arguments, batchEnabled)
          ZQuery.fromRequest(fetch)(dataSource).flatMap {
            case ListValue(values) =>
              val filteredValues = fetcher.extend.filterBatchResults match {
                case Some(filter) => values.filter(value => filter(parentObject, value.asObjectValue))
                case _            => values
              }
              (field.fields, filteredValues) match {
                // special case of a single item we don't want to wrap in a list (e.g. entity fetching)
                case (field :: Nil, value :: Nil) if field.eliminate =>
                  resolveField(field, value)
                case _                                               =>
                  foreach(filteredValues)(resolveObject(field.fields, _)).map(ListValue.apply)
              }
            case value             =>
              resolveObject(field.fields, value)
          }
        case None           => ZQuery.fail(ExecutionError(s"Subgraph ${fetcher.extend.sourceGraph} not found"))
      }

    def resolveObject(fields: List[Resolver.Field], value: ResponseValue): ZQuery[R, ExecutionError, ObjectValue] =
      foreach(fields)(field => resolveField(field, value).map(field.outputName -> _)).map(ObjectValue.apply)

    resolveField(root, NullValue)
  }

  private def getFieldsToFetch(fields: List[Resolver.Field], targets: Option[Set[String]]): List[Field] =
    fields
      .flatMap(f =>
        f.resolver match {
          case _: Extractor    =>
            List(Field(f.name, Types.string, None, fields = getFieldsToFetch(f.fields, None), targets = targets))
          case Fetcher(extend) =>
            extend.argumentMappings.keys.toList.map(Field(_, Types.string, None))
        }
      )
      .distinct
}
