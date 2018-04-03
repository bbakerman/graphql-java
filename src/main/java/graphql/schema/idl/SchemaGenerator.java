package graphql.schema.idl;

import graphql.GraphQLError;
import graphql.PublicApi;
import graphql.introspection.Introspection;
import graphql.introspection.Introspection.DirectiveLocation;
import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.InterfaceTypeExtensionDefinition;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.NodeParentTree;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.ScalarTypeExtensionDefinition;
import graphql.language.SchemaDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.UnionTypeExtensionDefinition;
import graphql.language.Value;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetcherFactories;
import graphql.schema.DataFetcherFactory;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import graphql.schema.TypeResolverProxy;
import graphql.schema.idl.errors.NotAnInputTypeError;
import graphql.schema.idl.errors.NotAnOutputTypeError;
import graphql.schema.idl.errors.SchemaProblem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.Assert.assertNotNull;
import static graphql.introspection.Introspection.DirectiveLocation.ARGUMENT_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM_VALUE;
import static graphql.introspection.Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.INPUT_OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.SCALAR;
import static graphql.introspection.Introspection.DirectiveLocation.UNION;
import static graphql.schema.GraphQLEnumValueDefinition.newEnumValueDefinition;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static java.util.Collections.emptyList;

/**
 * This can generate a working runtime schema from a type registry and runtime wiring
 */
@PublicApi
public class SchemaGenerator {

    /**
     * These options control how the schema generation works
     */
    public static class Options {
        private final boolean enforceSchemaDirectives;

        Options(boolean enforceSchemaDirectives) {
            this.enforceSchemaDirectives = enforceSchemaDirectives;
        }

        /**
         * This controls whether schema directives MUST be declared using
         * directive definition syntax before use.
         *
         * @return true if directives must be fully declared; the default is false
         */
        public boolean isEnforceSchemaDirectives() {
            return enforceSchemaDirectives;
        }

        public static Options defaultOptions() {
            return new Options(false);
        }

        /**
         * This controls whether schema directives MUST be declared using
         * directive definition syntax before use.
         *
         * @param flag the value to use
         *
         * @return the new options
         */
        public Options enforceSchemaDirectives(boolean flag) {
            return new Options(flag);
        }

    }


    /**
     * We pass this around so we know what we have defined in a stack like manner plus
     * it gives us helper functions
     */
    class BuildContext {
        private final TypeDefinitionRegistry typeRegistry;
        private final RuntimeWiring wiring;
        private final Deque<String> typeStack = new ArrayDeque<>();
        private final Deque<Node> nodeStack = new ArrayDeque<>();

        private final Map<String, GraphQLOutputType> outputGTypes = new HashMap<>();
        private final Map<String, GraphQLInputType> inputGTypes = new HashMap<>();
        private final Map<String, Object> directiveBehaviourContext = new HashMap<>();
        private final Set<GraphQLDirective> directiveDefinitions = new HashSet<>();

        BuildContext(TypeDefinitionRegistry typeRegistry, RuntimeWiring wiring) {
            this.typeRegistry = typeRegistry;
            this.wiring = wiring;
        }

        public TypeDefinitionRegistry getTypeRegistry() {
            return typeRegistry;
        }

        @SuppressWarnings({"OptionalGetWithoutIsPresent", "ConstantConditions"})
        TypeDefinition getTypeDefinition(Type type) {
            return typeRegistry.getType(type).get();
        }

        boolean stackContains(TypeInfo typeInfo) {
            return typeStack.contains(typeInfo.getName());
        }

        void push(TypeInfo typeInfo) {
            typeStack.push(typeInfo.getName());
        }

        void pop() {
            typeStack.pop();
        }

        void enterNode(Node node) {
            nodeStack.push(node);
        }

        <T> T exitNode(T t) {
            nodeStack.pop();
            return t;
        }

        SchemaGeneratorDirectiveHelper.Parameters mkBehaviourParams() {
            List<NamedNode> list = nodeStack.stream()
                    .filter(NamedNode.class::isInstance)
                    .map(NamedNode.class::cast)
                    .collect(Collectors.toList());
            Deque<NamedNode> deque = new ArrayDeque<>(list);
            return new SchemaGeneratorDirectiveHelper.Parameters(typeRegistry, wiring, new NodeParentTree(deque), directiveBehaviourContext);
        }

        GraphQLOutputType hasOutputType(TypeDefinition typeDefinition) {
            return outputGTypes.get(typeDefinition.getName());
        }

        GraphQLInputType hasInputType(TypeDefinition typeDefinition) {
            return inputGTypes.get(typeDefinition.getName());
        }

        void put(GraphQLOutputType outputType) {
            outputGTypes.put(outputType.getName(), outputType);
            // certain types can be both input and output types, for example enums
            if (outputType instanceof GraphQLInputType) {
                inputGTypes.put(outputType.getName(), (GraphQLInputType) outputType);
            }
        }

        void put(GraphQLInputType inputType) {
            inputGTypes.put(inputType.getName(), inputType);
            // certain types can be both input and output types, for example enums
            if (inputType instanceof GraphQLOutputType) {
                outputGTypes.put(inputType.getName(), (GraphQLOutputType) inputType);
            }
        }

        RuntimeWiring getWiring() {
            return wiring;
        }

        public void setDirectiveDefinitions(Set<GraphQLDirective> directiveDefinitions) {
            this.directiveDefinitions.addAll(directiveDefinitions);
        }

        public Set<GraphQLDirective> getDirectiveDefinitions() {
            return directiveDefinitions;
        }
    }

    private final SchemaTypeChecker typeChecker = new SchemaTypeChecker();
    private final SchemaGeneratorHelper schemaGeneratorHelper = new SchemaGeneratorHelper();
    private final SchemaGeneratorDirectiveHelper directiveBehaviour = new SchemaGeneratorDirectiveHelper();

    public SchemaGenerator() {
    }

    /**
     * This will take a {@link TypeDefinitionRegistry} and a {@link RuntimeWiring} and put them together to create a executable schema
     *
     * @param typeRegistry this can be obtained via {@link SchemaParser#parse(String)}
     * @param wiring       this can be built using {@link RuntimeWiring#newRuntimeWiring()}
     *
     * @return an executable schema
     *
     * @throws SchemaProblem if there are problems in assembling a schema such as missing type resolvers or no operations defined
     */
    public GraphQLSchema makeExecutableSchema(TypeDefinitionRegistry typeRegistry, RuntimeWiring wiring) throws SchemaProblem {
        return makeExecutableSchema(Options.defaultOptions(), typeRegistry, wiring);
    }

    /**
     * This will take a {@link TypeDefinitionRegistry} and a {@link RuntimeWiring} and put them together to create a executable schema
     * controlled by the provided options.
     *
     * @param options      the controlling options
     * @param typeRegistry this can be obtained via {@link SchemaParser#parse(String)}
     * @param wiring       this can be built using {@link RuntimeWiring#newRuntimeWiring()}
     *
     * @return an executable schema
     *
     * @throws SchemaProblem if there are problems in assembling a schema such as missing type resolvers or no operations defined
     */
    public GraphQLSchema makeExecutableSchema(Options options, TypeDefinitionRegistry typeRegistry, RuntimeWiring wiring) throws SchemaProblem {
        List<GraphQLError> errors = typeChecker.checkTypeRegistry(typeRegistry, wiring, options.enforceSchemaDirectives);
        if (!errors.isEmpty()) {
            throw new SchemaProblem(errors);
        }
        BuildContext buildCtx = new BuildContext(typeRegistry, wiring);

        return makeExecutableSchemaImpl(buildCtx);
    }

    private GraphQLSchema makeExecutableSchemaImpl(BuildContext buildCtx) {
        GraphQLObjectType query;
        GraphQLObjectType mutation;
        GraphQLObjectType subscription;

        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema();

        Set<GraphQLDirective> additionalDirectives = buildAdditionalDirectives(buildCtx);
        schemaBuilder.additionalDirectives(additionalDirectives);
        buildCtx.setDirectiveDefinitions(additionalDirectives);

        //
        // Schema can be missing if the type is called 'Query'.  Pre flight checks have checked that!
        //
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        if (!typeRegistry.schemaDefinition().isPresent()) {
            @SuppressWarnings({"OptionalGetWithoutIsPresent", "ConstantConditions"})
            TypeDefinition queryTypeDef = typeRegistry.getType("Query").get();

            query = buildOutputType(buildCtx, new TypeName(queryTypeDef.getName()));
            schemaBuilder.query(query);

            Optional<TypeDefinition> mutationTypeDef = typeRegistry.getType("Mutation");
            if (mutationTypeDef.isPresent()) {
                mutation = buildOutputType(buildCtx, new TypeName(mutationTypeDef.get().getName()));
                schemaBuilder.mutation(mutation);
            }
            Optional<TypeDefinition> subscriptionTypeDef = typeRegistry.getType("Subscription");
            if (subscriptionTypeDef.isPresent()) {
                subscription = buildOutputType(buildCtx, new TypeName(subscriptionTypeDef.get().getName()));
                schemaBuilder.subscription(subscription);
            }
        } else {
            SchemaDefinition schemaDefinition = typeRegistry.schemaDefinition().get();
            List<OperationTypeDefinition> operationTypes = schemaDefinition.getOperationTypeDefinitions();

            // pre-flight checked via checker
            @SuppressWarnings({"OptionalGetWithoutIsPresent", "ConstantConditions"})
            OperationTypeDefinition queryOp = operationTypes.stream().filter(op -> "query".equals(op.getName())).findFirst().get();
            Optional<OperationTypeDefinition> mutationOp = operationTypes.stream().filter(op -> "mutation".equals(op.getName())).findFirst();
            Optional<OperationTypeDefinition> subscriptionOp = operationTypes.stream().filter(op -> "subscription".equals(op.getName())).findFirst();

            query = buildOperation(buildCtx, queryOp);
            schemaBuilder.query(query);

            if (mutationOp.isPresent()) {
                mutation = buildOperation(buildCtx, mutationOp.get());
                schemaBuilder.mutation(mutation);
            }
            if (subscriptionOp.isPresent()) {
                subscription = buildOperation(buildCtx, subscriptionOp.get());
                schemaBuilder.subscription(subscription);
            }
        }

        Set<GraphQLType> additionalTypes = buildAdditionalTypes(buildCtx);
        schemaBuilder.additionalTypes(additionalTypes);

        schemaBuilder.fieldVisibility(buildCtx.getWiring().getFieldVisibility());

        return schemaBuilder.build();
    }

    private GraphQLObjectType buildOperation(BuildContext buildCtx, OperationTypeDefinition operation) {
        Type type = operation.getType();

        return buildOutputType(buildCtx, type);
    }

    /**
     * We build the query / mutation / subscription path as a tree of referenced types
     * but then we build the rest of the types specified and put them in as additional types
     *
     * @param buildCtx the context we need to work out what we are doing
     *
     * @return the additional types not referenced from the top level operations
     */
    private Set<GraphQLType> buildAdditionalTypes(BuildContext buildCtx) {
        Set<GraphQLType> additionalTypes = new HashSet<>();
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        typeRegistry.types().values().forEach(typeDefinition -> {
            TypeName typeName = new TypeName(typeDefinition.getName());
            if (typeDefinition instanceof InputObjectTypeDefinition) {
                if (buildCtx.hasInputType(typeDefinition) == null) {
                    additionalTypes.add(buildInputType(buildCtx, typeName));
                }
            } else {
                if (buildCtx.hasOutputType(typeDefinition) == null) {
                    additionalTypes.add(buildOutputType(buildCtx, typeName));
                }
            }
        });
        return additionalTypes;
    }

    private Set<GraphQLDirective> buildAdditionalDirectives(BuildContext buildCtx) {
        Set<GraphQLDirective> additionalDirectives = new HashSet<>();
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        typeRegistry.getDirectiveDefinitions().values().forEach(directiveDefinition -> {
            Function<Type, GraphQLInputType> inputTypeFactory = inputType -> buildInputType(buildCtx, inputType);
            GraphQLDirective directive = schemaGeneratorHelper.buildDirectiveFromDefinition(directiveDefinition, inputTypeFactory);
            additionalDirectives.add(directive);
        });
        return additionalDirectives;
    }

    /**
     * This is the main recursive spot that builds out the various forms of Output types
     *
     * @param buildCtx the context we need to work out what we are doing
     * @param rawType  the type to be built
     *
     * @return an output type
     */
    @SuppressWarnings("unchecked")
    private <T extends GraphQLOutputType> T buildOutputType(BuildContext buildCtx, Type rawType) {

        TypeDefinition typeDefinition = buildCtx.getTypeDefinition(rawType);
        TypeInfo typeInfo = TypeInfo.typeInfo(rawType);

        GraphQLOutputType outputType = buildCtx.hasOutputType(typeDefinition);
        if (outputType != null) {
            return typeInfo.decorate(outputType);
        }

        if (buildCtx.stackContains(typeInfo)) {
            // we have circled around so put in a type reference and fix it up later
            // otherwise we will go into an infinite loop
            return typeInfo.decorate(typeRef(typeInfo.getName()));
        }

        buildCtx.push(typeInfo);

        if (typeDefinition instanceof ObjectTypeDefinition) {
            outputType = buildObjectType(buildCtx, (ObjectTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof InterfaceTypeDefinition) {
            outputType = buildInterfaceType(buildCtx, (InterfaceTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof UnionTypeDefinition) {
            outputType = buildUnionType(buildCtx, (UnionTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof EnumTypeDefinition) {
            outputType = buildEnumType(buildCtx, (EnumTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof ScalarTypeDefinition) {
            outputType = buildScalar(buildCtx, (ScalarTypeDefinition) typeDefinition);
        } else {
            // typeDefinition is not a valid output type
            throw new NotAnOutputTypeError(typeDefinition);
        }

        buildCtx.put(outputType);
        buildCtx.pop();
        return (T) typeInfo.decorate(outputType);
    }

    private GraphQLInputType buildInputType(BuildContext buildCtx, Type rawType) {

        TypeDefinition typeDefinition = buildCtx.getTypeDefinition(rawType);
        TypeInfo typeInfo = TypeInfo.typeInfo(rawType);

        GraphQLInputType inputType = buildCtx.hasInputType(typeDefinition);
        if (inputType != null) {
            return typeInfo.decorate(inputType);
        }

        if (buildCtx.stackContains(typeInfo)) {
            // we have circled around so put in a type reference and fix it later
            return typeInfo.decorate(typeRef(typeInfo.getName()));
        }

        buildCtx.push(typeInfo);

        if (typeDefinition instanceof InputObjectTypeDefinition) {
            inputType = buildInputObjectType(buildCtx, (InputObjectTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof EnumTypeDefinition) {
            inputType = buildEnumType(buildCtx, (EnumTypeDefinition) typeDefinition);
        } else if (typeDefinition instanceof ScalarTypeDefinition) {
            inputType = buildScalar(buildCtx, (ScalarTypeDefinition) typeDefinition);
        } else {
            // typeDefinition is not a valid InputType
            throw new NotAnInputTypeError(typeDefinition);
        }

        buildCtx.put(inputType);
        buildCtx.pop();
        return typeInfo.decorate(inputType);
    }

    private GraphQLObjectType buildObjectType(BuildContext buildCtx, ObjectTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        GraphQLObjectType.Builder builder = GraphQLObjectType.newObject();
        builder.definition(typeDefinition);
        builder.name(typeDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(typeDefinition, typeDefinition.getDescription()));

        List<ObjectTypeExtensionDefinition> extensions = objectTypeExtensions(typeDefinition, buildCtx);
        builder.withDirectives(
                buildDirectives(typeDefinition.getDirectives(),
                        directivesOf(extensions), OBJECT, buildCtx.getDirectiveDefinitions())
        );

        typeDefinition.getFieldDefinitions().forEach(fieldDef -> {
            GraphQLFieldDefinition newFieldDefinition = buildField(buildCtx, typeDefinition, fieldDef);
            builder.field(newFieldDefinition);
        });

        extensions.forEach(extension -> extension.getFieldDefinitions().forEach(fieldDef -> {
            GraphQLFieldDefinition newFieldDefinition = buildField(buildCtx, typeDefinition, fieldDef);
            if (!builder.hasField(newFieldDefinition.getName())) {
                builder.field(newFieldDefinition);
            }
        }));

        buildObjectTypeInterfaces(buildCtx, typeDefinition, builder, extensions);

        GraphQLObjectType objectType = builder.build();
        objectType = directiveBehaviour.onObject(objectType, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(objectType);
    }

    private void buildObjectTypeInterfaces(BuildContext buildCtx, ObjectTypeDefinition typeDefinition, GraphQLObjectType.Builder builder, List<ObjectTypeExtensionDefinition> extensions) {
        Map<String, GraphQLInterfaceType> interfaces = new LinkedHashMap<>();
        typeDefinition.getImplements().forEach(type -> {
            GraphQLInterfaceType newInterfaceType = buildOutputType(buildCtx, type);
            interfaces.put(newInterfaceType.getName(), newInterfaceType);
        });

        extensions.forEach(extension -> extension.getImplements().forEach(type -> {
            GraphQLInterfaceType interfaceType = buildOutputType(buildCtx, type);
            if (!interfaces.containsKey(interfaceType.getName())) {
                interfaces.put(interfaceType.getName(), interfaceType);
            }
        }));

        interfaces.values().forEach(builder::withInterface);
    }


    private GraphQLInterfaceType buildInterfaceType(BuildContext buildCtx, InterfaceTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        GraphQLInterfaceType.Builder builder = GraphQLInterfaceType.newInterface();
        builder.definition(typeDefinition);
        builder.name(typeDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(typeDefinition, typeDefinition.getDescription()));

        builder.typeResolver(getTypeResolverForInterface(buildCtx, typeDefinition));

        List<InterfaceTypeExtensionDefinition> extensions = interfaceTypeExtensions(typeDefinition, buildCtx);
        builder.withDirectives(
                buildDirectives(typeDefinition.getDirectives(),
                        directivesOf(extensions), OBJECT, buildCtx.getDirectiveDefinitions())
        );

        typeDefinition.getFieldDefinitions().forEach(fieldDef ->
                builder.field(buildField(buildCtx, typeDefinition, fieldDef)));

        extensions.forEach(extension -> extension.getFieldDefinitions().forEach(fieldDef -> {
            GraphQLFieldDefinition field = buildField(buildCtx, typeDefinition, fieldDef);
            if (!builder.hasField(field.getName())) {
                builder.field(field);
            }
        }));

        GraphQLInterfaceType interfaceType = builder.build();
        interfaceType = directiveBehaviour.onInterface(interfaceType, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(interfaceType);
    }

    private GraphQLUnionType buildUnionType(BuildContext buildCtx, UnionTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        GraphQLUnionType.Builder builder = GraphQLUnionType.newUnionType();
        builder.definition(typeDefinition);
        builder.name(typeDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(typeDefinition, typeDefinition.getDescription()));
        builder.typeResolver(getTypeResolverForUnion(buildCtx, typeDefinition));

        List<UnionTypeExtensionDefinition> extensions = unionTypeExtensions(typeDefinition, buildCtx);

        typeDefinition.getMemberTypes().forEach(mt -> {
            GraphQLOutputType outputType = buildOutputType(buildCtx, mt);
            if (outputType instanceof GraphQLTypeReference) {
                builder.possibleType((GraphQLTypeReference) outputType);
            } else {
                builder.possibleType((GraphQLObjectType) outputType);
            }
        });

        builder.withDirectives(
                buildDirectives(typeDefinition.getDirectives(),
                        directivesOf(extensions), UNION, buildCtx.getDirectiveDefinitions())
        );

        extensions.forEach(extension -> extension.getMemberTypes().forEach(mt -> {
                    GraphQLOutputType outputType = buildOutputType(buildCtx, mt);
                    if (!builder.containType(outputType.getName())) {
                        if (outputType instanceof GraphQLTypeReference) {
                            builder.possibleType((GraphQLTypeReference) outputType);
                        } else {
                            builder.possibleType((GraphQLObjectType) outputType);
                        }
                    }
                }
        ));

        GraphQLUnionType unionType = builder.build();
        unionType = directiveBehaviour.onUnion(unionType, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(unionType);
    }

    private GraphQLEnumType buildEnumType(BuildContext buildCtx, EnumTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        GraphQLEnumType.Builder builder = GraphQLEnumType.newEnum();
        builder.definition(typeDefinition);
        builder.name(typeDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(typeDefinition, typeDefinition.getDescription()));

        List<EnumTypeExtensionDefinition> extensions = enumTypeExtensions(typeDefinition, buildCtx);

        EnumValuesProvider enumValuesProvider = buildCtx.getWiring().getEnumValuesProviders().get(typeDefinition.getName());
        typeDefinition.getEnumValueDefinitions().forEach(evd -> {
            GraphQLEnumValueDefinition enumValueDefinition = buildEnumValue(buildCtx, typeDefinition, enumValuesProvider, evd);
            builder.value(enumValueDefinition);
        });

        extensions.forEach(extension -> extension.getEnumValueDefinitions().forEach(evd -> {
            GraphQLEnumValueDefinition enumValueDefinition = buildEnumValue(buildCtx, typeDefinition, enumValuesProvider, evd);
            if (!builder.hasValue(enumValueDefinition.getName())) {
                builder.value(enumValueDefinition);
            }
        }));

        builder.withDirectives(
                buildDirectives(typeDefinition.getDirectives(),
                        directivesOf(extensions), ENUM, buildCtx.getDirectiveDefinitions())
        );

        GraphQLEnumType enumType = builder.build();
        enumType = directiveBehaviour.onEnum(enumType, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(enumType);
    }

    private GraphQLEnumValueDefinition buildEnumValue(BuildContext buildCtx, EnumTypeDefinition typeDefinition, EnumValuesProvider enumValuesProvider, EnumValueDefinition evd) {
        buildCtx.enterNode(evd);

        String description = schemaGeneratorHelper.buildDescription(evd, evd.getDescription());
        String deprecation = schemaGeneratorHelper.buildDeprecationReason(evd.getDirectives());

        Object value;
        if (enumValuesProvider != null) {
            value = enumValuesProvider.getValue(evd.getName());
            assertNotNull(value, "EnumValuesProvider for %s returned null for %s", typeDefinition.getName(), evd.getName());
        } else {
            value = evd.getName();
        }
        GraphQLEnumValueDefinition valueDefinition = newEnumValueDefinition()
                .name(evd.getName())
                .value(value)
                .description(description)
                .deprecationReason(deprecation)
                .withDirectives(
                        buildDirectives(evd.getDirectives(),
                                emptyList(), ENUM_VALUE, buildCtx.getDirectiveDefinitions())
                )
                .build();
        valueDefinition = directiveBehaviour.onEnumValue(valueDefinition, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(valueDefinition);
    }

    private GraphQLScalarType buildScalar(BuildContext buildCtx, ScalarTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        RuntimeWiring runtimeWiring = buildCtx.getWiring();
        WiringFactory wiringFactory = runtimeWiring.getWiringFactory();
        List<ScalarTypeExtensionDefinition> extensions = scalarTypeExtensions(typeDefinition, buildCtx);

        ScalarWiringEnvironment environment = new ScalarWiringEnvironment(typeRegistry, typeDefinition, extensions);

        GraphQLScalarType scalar;
        if (wiringFactory.providesScalar(environment)) {
            scalar = wiringFactory.getScalar(environment);
        } else {
            scalar = buildCtx.getWiring().getScalars().get(typeDefinition.getName());
        }

        if (!ScalarInfo.isStandardScalar(scalar) && !ScalarInfo.isGraphqlSpecifiedScalar(scalar)) {
            scalar = scalar.transform(builder -> builder.withDirectives(
                    buildDirectives(typeDefinition.getDirectives(),
                            directivesOf(extensions), SCALAR)
            ));
            //
            // only allow modification of custom scalars
            scalar = directiveBehaviour.onScalar(scalar, buildCtx.mkBehaviourParams());
        }

        return buildCtx.exitNode(scalar);
    }

    private GraphQLFieldDefinition buildField(BuildContext buildCtx, TypeDefinition parentType, FieldDefinition fieldDef) {
        buildCtx.enterNode(fieldDef);

        GraphQLFieldDefinition.Builder builder = GraphQLFieldDefinition.newFieldDefinition();
        builder.definition(fieldDef);
        builder.name(fieldDef.getName());
        builder.description(schemaGeneratorHelper.buildDescription(fieldDef, fieldDef.getDescription()));
        builder.deprecate(schemaGeneratorHelper.buildDeprecationReason(fieldDef.getDirectives()));

        GraphQLDirective[] directives = buildDirectives(fieldDef.getDirectives(),
                Collections.emptyList(), Introspection.DirectiveLocation.FIELD, buildCtx.getDirectiveDefinitions());
        builder.withDirectives(
                directives
        );

        fieldDef.getInputValueDefinitions().forEach(inputValueDefinition ->
                builder.argument(buildArgument(buildCtx, inputValueDefinition)));

        GraphQLOutputType fieldType = buildOutputType(buildCtx, fieldDef.getType());
        builder.type(fieldType);

        builder.dataFetcherFactory(buildDataFetcherFactory(buildCtx, parentType, fieldDef, fieldType, Arrays.asList(directives)));

        GraphQLFieldDefinition fieldDefinition = builder.build();
        fieldDefinition = directiveBehaviour.onField(fieldDefinition, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(fieldDefinition);
    }

    private DataFetcherFactory buildDataFetcherFactory(BuildContext buildCtx, TypeDefinition parentType, FieldDefinition fieldDef, GraphQLOutputType fieldType, List<GraphQLDirective> directives) {
        String fieldName = fieldDef.getName();
        String parentTypeName = parentType.getName();
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        RuntimeWiring runtimeWiring = buildCtx.getWiring();
        WiringFactory wiringFactory = runtimeWiring.getWiringFactory();

        FieldWiringEnvironment wiringEnvironment = new FieldWiringEnvironment(typeRegistry, parentType, fieldDef, fieldType, directives);

        DataFetcherFactory<?> dataFetcherFactory;
        if (wiringFactory.providesDataFetcherFactory(wiringEnvironment)) {
            dataFetcherFactory = wiringFactory.getDataFetcherFactory(wiringEnvironment);
            assertNotNull(dataFetcherFactory, "The WiringFactory indicated it provides a data fetcher factory but then returned null");
        } else {
            //
            // ok they provide a data fetcher directly
            DataFetcher<?> dataFetcher;
            if (wiringFactory.providesDataFetcher(wiringEnvironment)) {
                dataFetcher = wiringFactory.getDataFetcher(wiringEnvironment);
                assertNotNull(dataFetcher, "The WiringFactory indicated it provides a data fetcher but then returned null");
            } else {
                dataFetcher = runtimeWiring.getDataFetcherForType(parentTypeName).get(fieldName);
                if (dataFetcher == null) {
                    dataFetcher = runtimeWiring.getDefaultDataFetcherForType(parentTypeName);
                    if (dataFetcher == null) {
                        dataFetcher = wiringFactory.getDefaultDataFetcher(wiringEnvironment);
                        assertNotNull(dataFetcher, "The WiringFactory indicated MUST provide a default data fetcher as part of its contract");
                    }
                }
            }
            dataFetcherFactory = DataFetcherFactories.useDataFetcher(dataFetcher);
        }

        return dataFetcherFactory;
    }

    private GraphQLInputObjectType buildInputObjectType(BuildContext buildCtx, InputObjectTypeDefinition typeDefinition) {
        buildCtx.enterNode(typeDefinition);

        GraphQLInputObjectType.Builder builder = GraphQLInputObjectType.newInputObject();
        builder.definition(typeDefinition);
        builder.name(typeDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(typeDefinition, typeDefinition.getDescription()));

        List<InputObjectTypeExtensionDefinition> extensions = inputObjectTypeExtensions(typeDefinition, buildCtx);

        builder.withDirectives(
                buildDirectives(typeDefinition.getDirectives(),
                        directivesOf(extensions), INPUT_OBJECT, buildCtx.getDirectiveDefinitions())
        );

        typeDefinition.getInputValueDefinitions().forEach(inputValue ->
                builder.field(buildInputField(buildCtx, inputValue)));

        extensions.forEach(extension -> extension.getInputValueDefinitions().forEach(inputValueDefinition -> {
            GraphQLInputObjectField inputField = buildInputField(buildCtx, inputValueDefinition);
            if (!builder.hasField(inputField.getName())) {
                builder.field(inputField);
            }
        }));

        GraphQLInputObjectType inputObjectType = builder.build();
        inputObjectType = directiveBehaviour.onInputObjectType(inputObjectType, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(inputObjectType);
    }

    private GraphQLInputObjectField buildInputField(BuildContext buildCtx, InputValueDefinition fieldDef) {
        buildCtx.enterNode(fieldDef);

        GraphQLInputObjectField.Builder fieldBuilder = GraphQLInputObjectField.newInputObjectField();
        fieldBuilder.definition(fieldDef);
        fieldBuilder.name(fieldDef.getName());
        fieldBuilder.description(schemaGeneratorHelper.buildDescription(fieldDef, fieldDef.getDescription()));

        // currently the spec doesnt allow deprecations on InputValueDefinitions but it should!
        //fieldBuilder.deprecate(buildDeprecationReason(fieldDef.getDirectives()));
        GraphQLInputType inputType = buildInputType(buildCtx, fieldDef.getType());
        fieldBuilder.type(inputType);
        Value defaultValue = fieldDef.getDefaultValue();
        if (defaultValue != null) {
            fieldBuilder.defaultValue(schemaGeneratorHelper.buildValue(defaultValue, inputType));
        }

        fieldBuilder.withDirectives(
                buildDirectives(fieldDef.getDirectives(),
                        emptyList(), INPUT_FIELD_DEFINITION, buildCtx.getDirectiveDefinitions())
        );

        GraphQLInputObjectField inputObjectField = fieldBuilder.build();
        inputObjectField = directiveBehaviour.onInputObjectField(inputObjectField, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(inputObjectField);
    }

    private GraphQLArgument buildArgument(BuildContext buildCtx, InputValueDefinition valueDefinition) {
        buildCtx.enterNode(valueDefinition);

        GraphQLArgument.Builder builder = GraphQLArgument.newArgument();
        builder.definition(valueDefinition);
        builder.name(valueDefinition.getName());
        builder.description(schemaGeneratorHelper.buildDescription(valueDefinition, valueDefinition.getDescription()));
        GraphQLInputType inputType = buildInputType(buildCtx, valueDefinition.getType());
        builder.type(inputType);
        Value defaultValue = valueDefinition.getDefaultValue();
        if (defaultValue != null) {
            builder.defaultValue(schemaGeneratorHelper.buildValue(defaultValue, inputType));
        }

        builder.withDirectives(
                buildDirectives(valueDefinition.getDirectives(),
                        Collections.emptyList(), ARGUMENT_DEFINITION)
        );

        GraphQLArgument argument = builder.build();
        argument = directiveBehaviour.onArgument(argument, buildCtx.mkBehaviourParams());
        return buildCtx.exitNode(argument);
    }

    @SuppressWarnings("Duplicates")
    private TypeResolver getTypeResolverForUnion(BuildContext buildCtx, UnionTypeDefinition unionType) {
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        RuntimeWiring wiring = buildCtx.getWiring();
        WiringFactory wiringFactory = wiring.getWiringFactory();

        TypeResolver typeResolver;
        UnionWiringEnvironment environment = new UnionWiringEnvironment(typeRegistry, unionType);

        if (wiringFactory.providesTypeResolver(environment)) {
            typeResolver = wiringFactory.getTypeResolver(environment);
            assertNotNull(typeResolver, "The WiringFactory indicated it union provides a type resolver but then returned null");

        } else {
            typeResolver = wiring.getTypeResolvers().get(unionType.getName());
            if (typeResolver == null) {
                // this really should be checked earlier via a pre-flight check
                typeResolver = new TypeResolverProxy();
            }
        }

        return typeResolver;
    }

    @SuppressWarnings("Duplicates")
    private TypeResolver getTypeResolverForInterface(BuildContext buildCtx, InterfaceTypeDefinition interfaceType) {
        TypeDefinitionRegistry typeRegistry = buildCtx.getTypeRegistry();
        RuntimeWiring wiring = buildCtx.getWiring();
        WiringFactory wiringFactory = wiring.getWiringFactory();

        TypeResolver typeResolver;

        InterfaceWiringEnvironment environment = new InterfaceWiringEnvironment(typeRegistry, interfaceType);

        if (wiringFactory.providesTypeResolver(environment)) {
            typeResolver = wiringFactory.getTypeResolver(environment);
            assertNotNull(typeResolver, "The WiringFactory indicated it provides a interface type resolver but then returned null");

        } else {
            typeResolver = wiring.getTypeResolvers().get(interfaceType.getName());
            if (typeResolver == null) {
                // this really should be checked earlier via a pre-flight check
                typeResolver = new TypeResolverProxy();
            }
        }
        return typeResolver;
    }


    private GraphQLDirective[] buildDirectives(List<Directive> directives, List<Directive> extensionDirectives, DirectiveLocation directiveLocation, Set<GraphQLDirective> directiveDefinitions) {
        directives = directives == null ? emptyList() : directives;
        extensionDirectives = extensionDirectives == null ? emptyList() : extensionDirectives;
        Set<String> names = new HashSet<>();

        List<GraphQLDirective> output = new ArrayList<>();
        for (Directive directive : directives) {
            if (!names.contains(directive.getName())) {
                names.add(directive.getName());
                output.add(schemaGeneratorHelper.buildDirective(directive, directiveDefinitions, directiveLocation));
            }
        }
        for (Directive directive : extensionDirectives) {
            if (!names.contains(directive.getName())) {
                names.add(directive.getName());
                output.add(schemaGeneratorHelper.buildDirective(directive, directiveDefinitions, directiveLocation));
            }
        }
        return output.toArray(new GraphQLDirective[0]);
    }


    private List<ObjectTypeExtensionDefinition> objectTypeExtensions(ObjectTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.objectTypeExtensions().get(typeDefinition.getName()));
    }

    private List<InterfaceTypeExtensionDefinition> interfaceTypeExtensions(InterfaceTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.interfaceTypeExtensions().get(typeDefinition.getName()));
    }

    private List<UnionTypeExtensionDefinition> unionTypeExtensions(UnionTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.unionTypeExtensions().get(typeDefinition.getName()));
    }

    private List<EnumTypeExtensionDefinition> enumTypeExtensions(EnumTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.enumTypeExtensions().get(typeDefinition.getName()));
    }

    private List<ScalarTypeExtensionDefinition> scalarTypeExtensions(ScalarTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.scalarTypeExtensions().get(typeDefinition.getName()));
    }

    private List<InputObjectTypeExtensionDefinition> inputObjectTypeExtensions(InputObjectTypeDefinition typeDefinition, BuildContext buildCtx) {
        return nvl(buildCtx.typeRegistry.inputObjectTypeExtensions().get(typeDefinition.getName()));
    }

    private <T> List<T> nvl(List<T> list) {
        return list == null ? emptyList() : list;
    }

    private List<Directive> directivesOf(List<? extends TypeDefinition> typeDefinition) {
        Stream<Directive> directiveStream = typeDefinition.stream()
                .map(TypeDefinition::getDirectives).filter(Objects::nonNull)
                .flatMap(List::stream);
        return directiveStream.collect(Collectors.toList());
    }

}