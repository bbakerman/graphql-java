package graphql.schema.idl;

import graphql.Assert;
import graphql.PublicApi;
import graphql.language.AstPrinter;
import graphql.language.AstValueHelper;
import graphql.language.Description;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.schema.DefaultGraphqlTypeComparatorRegistry;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphqlTypeComparatorEnvironment;
import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.visibility.GraphqlFieldVisibility;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.Directives.DeprecatedDirective;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM_VALUE;
import static graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION;
import static graphql.schema.visibility.DefaultGraphqlFieldVisibility.DEFAULT_FIELD_VISIBILITY;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * This can print an in memory GraphQL schema back to a logical schema definition
 */
@PublicApi
public class SchemaPrinter {
    //
    // we use this so that we get the simple "@deprecated" as text and not a full exploded
    // text with arguments (but only when we auto add this)
    //
    private static final GraphQLDirective DeprecatedDirective4Printing = GraphQLDirective.newDirective()
            .name("deprecated")
            .validLocations(FIELD_DEFINITION, ENUM_VALUE)
            .build();

    /**
     * Options to use when printing a schema
     */
    public static class Options {

        private final boolean includeIntrospectionTypes;

        private final boolean includeScalars;

        private final boolean includeExtendedScalars;

        private final boolean includeSchemaDefinition;

        private final boolean includeDirectives;

        private final boolean descriptionsAsHashComments;

        private final GraphqlTypeComparatorRegistry comparatorRegistry;

        private Options(boolean includeIntrospectionTypes,
                        boolean includeScalars,
                        boolean includeExtendedScalars,
                        boolean includeSchemaDefinition,
                        boolean includeDirectives,
                        boolean descriptionsAsHashComments,
                        GraphqlTypeComparatorRegistry comparatorRegistry) {
            this.includeIntrospectionTypes = includeIntrospectionTypes;
            this.includeScalars = includeScalars;
            this.includeExtendedScalars = includeExtendedScalars;
            this.includeSchemaDefinition = includeSchemaDefinition;
            this.includeDirectives = includeDirectives;
            this.descriptionsAsHashComments = descriptionsAsHashComments;
            this.comparatorRegistry = comparatorRegistry;
        }

        public boolean isIncludeIntrospectionTypes() {
            return includeIntrospectionTypes;
        }

        public boolean isIncludeScalars() {
            return includeScalars;
        }

        public boolean isIncludeExtendedScalars() {
            return includeExtendedScalars;
        }

        public boolean isIncludeSchemaDefinition() {
            return includeSchemaDefinition;
        }

        public boolean isIncludeDirectives() {
            return includeDirectives;
        }

        public boolean isDescriptionsAsHashComments() {
            return descriptionsAsHashComments;
        }

        public GraphqlTypeComparatorRegistry getComparatorRegistry() {
            return comparatorRegistry;
        }

        public static Options defaultOptions() {
            return new Options(false, false, false,
                    false, true, false,
                    DefaultGraphqlTypeComparatorRegistry.defaultComparators());
        }

        /**
         * This will allow you to include introspection types that are contained in a schema
         *
         * @param flag whether to include them
         * @return options
         */
        public Options includeIntrospectionTypes(boolean flag) {
            return new Options(flag, this.includeScalars, this.includeExtendedScalars, this.includeSchemaDefinition, this.includeDirectives, this.descriptionsAsHashComments, this.comparatorRegistry);
        }

        /**
         * This will allow you to include scalar types that are contained in a schema
         *
         * @param flag whether to include them
         * @return options
         */
        public Options includeScalarTypes(boolean flag) {
            return new Options(this.includeIntrospectionTypes, flag, this.includeExtendedScalars, this.includeSchemaDefinition, this.includeDirectives, this.descriptionsAsHashComments, this.comparatorRegistry);
        }

        /**
         * This will allow you to include the graphql 'extended' scalar types that come with graphql-java such as
         * GraphQLBigDecimal or GraphQLBigInteger
         *
         * @param flag whether to include them
         * @return options
         */
        public Options includeExtendedScalarTypes(boolean flag) {
            return new Options(this.includeIntrospectionTypes, this.includeScalars, flag, this.includeSchemaDefinition, this.includeDirectives, this.descriptionsAsHashComments, this.comparatorRegistry);
        }

        /**
         * This will force the printing of the graphql schema definition even if the query, mutation, and/or subscription
         * types use the default names.  Some graphql parsers require this information even if the schema uses the
         * default type names.  The schema definition will always be printed if any of the query, mutation, or subscription
         * types do not use the default names.
         *
         * @param flag whether to force include the schema definition
         * @return options
         */
        public Options includeSchemaDefinition(boolean flag) {
            return new Options(this.includeIntrospectionTypes, this.includeScalars, this.includeExtendedScalars, flag, this.includeDirectives, this.descriptionsAsHashComments, this.comparatorRegistry);
        }

        /**
         * Allow to print directives. In some situations, auto-generated schemas contain a lot of directives that
         * make the printout noisy and having this flag would allow cleaner printout. On by default.
         *
         * @param flag whether to print directives
         * @return new instance of options
         */
        public Options includeDirectives(boolean flag) {
            return new Options(this.includeIntrospectionTypes, this.includeScalars,
                    this.includeExtendedScalars, this.includeSchemaDefinition, flag, this.descriptionsAsHashComments,
                    this.comparatorRegistry);
        }

        /**
         * Descriptions are defined as preceding string literals, however an older legacy
         * versions of SDL supported preceding '#' comments as
         * descriptions. Set this to true to enable this deprecated behavior.
         * This option is provided to ease adoption and may be removed in future versions.
         *
         * @param flag whether to print description as # comments
         * @return new instance of options
         */
        public Options descriptionsAsHashComments(boolean flag) {
            return new Options(this.includeIntrospectionTypes, this.includeScalars,
                    this.includeExtendedScalars, this.includeSchemaDefinition, this.includeDirectives, flag,
                    this.comparatorRegistry);
        }

        /**
         * The comparator registry controls the printing order for registered {@code GraphQLType}s.
         * <p>
         * The default is to sort elements by name but you can put in your own code to decide on the field order
         *
         * @param comparatorRegistry The registry containing the {@code Comparator} and environment scoping rules.
         * @return options
         */
        public Options setComparators(GraphqlTypeComparatorRegistry comparatorRegistry) {
            return new Options(this.includeIntrospectionTypes, this.includeScalars, this.includeExtendedScalars,
                    this.includeSchemaDefinition, this.includeDirectives, this.descriptionsAsHashComments,
                    comparatorRegistry);
        }
    }

    private final Map<Class, TypePrinter<?>> printers = new LinkedHashMap<>();

    private final Options options;

    public SchemaPrinter() {
        this(Options.defaultOptions());
    }

    public SchemaPrinter(Options options) {
        this.options = options;
        printers.put(GraphQLSchema.class, schemaPrinter());
        printers.put(GraphQLObjectType.class, objectPrinter());
        printers.put(GraphQLEnumType.class, enumPrinter());
        printers.put(GraphQLScalarType.class, scalarPrinter());
        printers.put(GraphQLInterfaceType.class, interfacePrinter());
        printers.put(GraphQLUnionType.class, unionPrinter());
        printers.put(GraphQLInputObjectType.class, inputObjectPrinter());
    }

    /**
     * This can print an in memory GraphQL IDL document back to a logical schema definition.
     * If you want to turn a Introspection query result into a Document (and then into a printed
     * schema) then use {@link graphql.introspection.IntrospectionResultToSchema#createSchemaDefinition(java.util.Map)}
     * first to get the {@link graphql.language.Document} and then print that.
     *
     * @param schemaIDL the parsed schema IDL
     * @return the logical schema definition
     */
    public String print(Document schemaIDL) {
        TypeDefinitionRegistry registry = new SchemaParser().buildRegistry(schemaIDL);
        return print(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry));
    }

    /**
     * This can print an in memory GraphQL schema back to a logical schema definition
     *
     * @param schema the schema in play
     * @return the logical schema definition
     */
    public String print(GraphQLSchema schema) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        GraphqlFieldVisibility visibility = schema.getCodeRegistry().getFieldVisibility();

        printer(schema.getClass()).print(out, schema, visibility);

        List<GraphQLType> typesAsList = schema.getAllTypesAsList()
                .stream()
                .sorted(Comparator.comparing(GraphQLNamedType::getName))
                .collect(toList());

        printType(out, typesAsList, GraphQLInterfaceType.class, visibility);
        printType(out, typesAsList, GraphQLUnionType.class, visibility);
        printType(out, typesAsList, GraphQLObjectType.class, visibility);
        printType(out, typesAsList, GraphQLEnumType.class, visibility);
        printType(out, typesAsList, GraphQLScalarType.class, visibility);
        printType(out, typesAsList, GraphQLInputObjectType.class, visibility);

        String result = sw.toString();
        if (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private interface TypePrinter<T> {

        void print(PrintWriter out, T type, GraphqlFieldVisibility visibility);

    }

    private boolean isIntrospectionType(GraphQLNamedType type) {
        return !options.isIncludeIntrospectionTypes() && type.getName().startsWith("__");
    }

    private TypePrinter<GraphQLScalarType> scalarPrinter() {
        return (out, type, visibility) -> {
            if (!options.isIncludeScalars()) {
                return;
            }
            boolean printScalar;
            if (ScalarInfo.isStandardScalar(type)) {
                printScalar = false;
                //noinspection RedundantIfStatement
                if (options.isIncludeExtendedScalars() && !ScalarInfo.isGraphqlSpecifiedScalar(type)) {
                    printScalar = true;
                }
            } else {
                printScalar = true;
            }
            if (printScalar) {
                printComments(out, type, "");
                out.format("scalar %s%s\n\n", type.getName(), directivesString(GraphQLScalarType.class, type.getDirectives()));
            }
        };
    }

    private TypePrinter<GraphQLEnumType> enumPrinter() {
        return (out, type, visibility) -> {
            if (isIntrospectionType(type)) {
                return;
            }

            GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                    .parentType(GraphQLEnumType.class)
                    .elementType(GraphQLEnumValueDefinition.class)
                    .build();
            Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

            printComments(out, type, "");
            out.format("enum %s%s {\n", type.getName(), directivesString(GraphQLEnumType.class, type.getDirectives()));
            List<GraphQLEnumValueDefinition> values = type.getValues()
                    .stream()
                    .sorted(comparator)
                    .collect(toList());
            for (GraphQLEnumValueDefinition enumValueDefinition : values) {
                printComments(out, enumValueDefinition, "  ");
                List<GraphQLDirective> enumValueDirectives = enumValueDefinition.getDirectives();
                if (enumValueDefinition.isDeprecated()) {
                    enumValueDirectives = addDeprecatedDirectiveIfNeeded(enumValueDirectives);
                }
                out.format("  %s%s\n", enumValueDefinition.getName(), directivesString(GraphQLEnumValueDefinition.class, enumValueDirectives));
            }
            out.format("}\n\n");
        };
    }

    private void printFieldDefinitions(PrintWriter out, Comparator<? super GraphQLSchemaElement> comparator, List<GraphQLFieldDefinition> fieldDefinitions) {
        fieldDefinitions
                .stream()
                .sorted(comparator)
                .forEach(fd -> {
                    printComments(out, fd, "  ");
                    List<GraphQLDirective> fieldDirectives = fd.getDirectives();
                    if (fd.isDeprecated()) {
                        fieldDirectives = addDeprecatedDirectiveIfNeeded(fieldDirectives);
                    }

                    out.format("  %s%s: %s%s\n",
                            fd.getName(), argsString(GraphQLFieldDefinition.class, fd.getArguments()), typeString(fd.getType()),
                            directivesString(GraphQLFieldDefinition.class, fieldDirectives));
                });
    }

    private TypePrinter<GraphQLInterfaceType> interfacePrinter() {
        return (out, type, visibility) -> {
            if (isIntrospectionType(type)) {
                return;
            }

            GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                    .parentType(GraphQLInterfaceType.class)
                    .elementType(GraphQLFieldDefinition.class)
                    .build();
            Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

            printComments(out, type, "");
            out.format("interface %s%s {\n", type.getName(), directivesString(GraphQLInterfaceType.class, type.getDirectives()));
            printFieldDefinitions(out, comparator, visibility.getFieldDefinitions(type));
            out.format("}\n\n");
        };
    }

    private TypePrinter<GraphQLUnionType> unionPrinter() {
        return (out, type, visibility) -> {
            if (isIntrospectionType(type)) {
                return;
            }

            GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                    .parentType(GraphQLUnionType.class)
                    .elementType(GraphQLOutputType.class)
                    .build();
            Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

            printComments(out, type, "");
            out.format("union %s%s = ", type.getName(), directivesString(GraphQLUnionType.class, type.getDirectives()));
            List<GraphQLNamedOutputType> types = type.getTypes()
                    .stream()
                    .sorted(comparator)
                    .collect(toList());
            for (int i = 0; i < types.size(); i++) {
                GraphQLNamedOutputType objectType = types.get(i);
                if (i > 0) {
                    out.format(" | ");
                }
                out.format("%s", objectType.getName());
            }
            out.format("\n\n");
        };
    }

    private TypePrinter<GraphQLObjectType> objectPrinter() {
        return (out, type, visibility) -> {
            if (isIntrospectionType(type)) {
                return;
            }
            printComments(out, type, "");
            if (type.getInterfaces().isEmpty()) {
                out.format("type %s%s {\n", type.getName(), directivesString(GraphQLObjectType.class, type.getDirectives()));
            } else {

                GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                        .parentType(GraphQLObjectType.class)
                        .elementType(GraphQLOutputType.class)
                        .build();
                Comparator<? super GraphQLSchemaElement> implementsComparator = options.comparatorRegistry.getComparator(environment);

                Stream<String> interfaceNames = type.getInterfaces()
                        .stream()
                        .sorted(implementsComparator)
                        .map(GraphQLNamedType::getName);
                out.format("type %s implements %s%s {\n",
                        type.getName(),
                        interfaceNames.collect(joining(" & ")),
                        directivesString(GraphQLObjectType.class, type.getDirectives()));
            }

            GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                    .parentType(GraphQLObjectType.class)
                    .elementType(GraphQLFieldDefinition.class)
                    .build();
            Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

            printFieldDefinitions(out, comparator, visibility.getFieldDefinitions(type));
            out.format("}\n\n");
        };
    }

    private TypePrinter<GraphQLInputObjectType> inputObjectPrinter() {
        return (out, type, visibility) -> {
            if (isIntrospectionType(type)) {
                return;
            }
            printComments(out, type, "");

            GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                    .parentType(GraphQLInputObjectType.class)
                    .elementType(GraphQLInputObjectField.class)
                    .build();
            Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

            out.format("input %s%s {\n", type.getName(), directivesString(GraphQLInputObjectType.class, type.getDirectives()));
            visibility.getFieldDefinitions(type)
                    .stream()
                    .sorted(comparator)
                    .forEach(fd -> {
                        printComments(out, fd, "  ");
                        out.format("  %s: %s",
                                fd.getName(), typeString(fd.getType()));
                        Object defaultValue = fd.getDefaultValue();
                        if (defaultValue != null) {
                            String astValue = printAst(defaultValue, fd.getType());
                            out.format(" = %s", astValue);
                        }
                        out.format(directivesString(GraphQLInputObjectField.class, fd.getDirectives()));
                        out.format("\n");
                    });
            out.format("}\n\n");
        };
    }

    private static String printAst(Object value, GraphQLInputType type) {
        return AstPrinter.printAst(AstValueHelper.astFromValue(value, type));
    }

    private TypePrinter<GraphQLSchema> schemaPrinter() {
        return (out, schema, visibility) -> {
            GraphQLObjectType queryType = schema.getQueryType();
            GraphQLObjectType mutationType = schema.getMutationType();
            GraphQLObjectType subscriptionType = schema.getSubscriptionType();

            // when serializing a GraphQL schema using the type system language, a
            // schema definition should be omitted if only uses the default root type names.
            boolean needsSchemaPrinted = options.isIncludeSchemaDefinition();

            if (!needsSchemaPrinted) {
                if (queryType != null && !queryType.getName().equals("Query")) {
                    needsSchemaPrinted = true;
                }
                if (mutationType != null && !mutationType.getName().equals("Mutation")) {
                    needsSchemaPrinted = true;
                }
                if (subscriptionType != null && !subscriptionType.getName().equals("Subscription")) {
                    needsSchemaPrinted = true;
                }
            }

            if (needsSchemaPrinted) {
                out.format("schema {\n");
                if (queryType != null) {
                    out.format("  query: %s\n", queryType.getName());
                }
                if (mutationType != null) {
                    out.format("  mutation: %s\n", mutationType.getName());
                }
                if (subscriptionType != null) {
                    out.format("  subscription: %s\n", subscriptionType.getName());
                }
                out.format("}\n\n");
            }

            List<GraphQLDirective> directives = getSchemaDirectives(schema);
            if (!directives.isEmpty()) {
                out.format("%s", directiveDefinitions(directives));
            }
        };
    }

    private List<GraphQLDirective> getSchemaDirectives(GraphQLSchema schema) {
        if (!options.isIncludeDirectives()) {
            return emptyList();
        }
        return schema.getDirectives();
    }

    String typeString(GraphQLType rawType) {
        return GraphQLTypeUtil.simplePrint(rawType);
    }

    String argsString(List<GraphQLArgument> arguments) {
        return argsString(null, arguments);
    }

    String argsString(Class<? extends GraphQLSchemaElement> parent, List<GraphQLArgument> arguments) {
        boolean hasDescriptions = arguments.stream().anyMatch(this::hasDescription);
        String halfPrefix = hasDescriptions ? "  " : "";
        String prefix = hasDescriptions ? "    " : "";
        int count = 0;
        StringBuilder sb = new StringBuilder();

        GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                .parentType(parent)
                .elementType(GraphQLArgument.class)
                .build();
        Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

        arguments = arguments
                .stream()
                .sorted(comparator)
                .collect(toList());
        for (GraphQLArgument argument : arguments) {
            if (count == 0) {
                sb.append("(");
            } else {
                sb.append(", ");
            }
            if (hasDescriptions) {
                sb.append("\n");
            }
            sb.append(printComments(argument, prefix));

            sb.append(prefix).append(argument.getName()).append(": ").append(typeString(argument.getType()));
            Object defaultValue = argument.getDefaultValue();
            if (defaultValue != null) {
                sb.append(" = ");
                sb.append(printAst(defaultValue, argument.getType()));
            }

            argument.getDirectives().stream()
                    .map(this::directiveString)
                    .filter(it -> !it.isEmpty())
                    .forEach(directiveString -> sb.append(" ").append(directiveString));

            count++;
        }
        if (count > 0) {
            if (hasDescriptions) {
                sb.append("\n");
            }
            sb.append(halfPrefix).append(")");
        }
        return sb.toString();
    }

    String directivesString(Class<? extends GraphQLSchemaElement> parent, List<GraphQLDirective> directives) {
        if (!options.isIncludeDirectives()) {
            if (hasDeprecatedDirective(directives)) {
                // @deprecated is special - we always print it if something is deprecated
                directives = Collections.singletonList(DeprecatedDirective);
            } else {
                return "";
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!directives.isEmpty()) {
            sb.append(" ");
        }

        GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                .parentType(parent)
                .elementType(GraphQLDirective.class)
                .build();
        Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

        directives = directives
                .stream()
                .sorted(comparator)
                .collect(toList());
        for (int i = 0; i < directives.size(); i++) {
            GraphQLDirective directive = directives.get(i);
            sb.append(directiveString(directive));
            if (i < directives.size() - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    private String directiveString(GraphQLDirective directive) {
        if (!options.includeDirectives) {
            // @deprecated is special - we always print it if something is deprecated
            if (!isDeprecatedDirective(directive)) {
                return "";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@").append(directive.getName());

        GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                .parentType(GraphQLDirective.class)
                .elementType(GraphQLArgument.class)
                .build();
        Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

        List<GraphQLArgument> args = directive.getArguments();
        args = args
                .stream()
                .sorted(comparator)
                .collect(toList());
        if (!args.isEmpty()) {
            sb.append("(");
            for (int i = 0; i < args.size(); i++) {
                GraphQLArgument arg = args.get(i);
                String argValue = null;
                if (arg.getValue() != null) {
                    argValue = printAst(arg.getValue(), arg.getType());
                } else if (arg.getDefaultValue() != null) {
                    argValue = printAst(arg.getDefaultValue(), arg.getType());
                }
                if (!isNullOrEmpty(argValue)) {
                    sb.append(arg.getName());
                    sb.append(" : ");
                    sb.append(argValue);
                    if (i < args.size() - 1) {
                        sb.append(", ");
                    }
                }
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private boolean isDeprecatedDirective(GraphQLDirective directive) {
        return directive.getName().equals(DeprecatedDirective.getName());
    }

    private boolean hasDeprecatedDirective(List<GraphQLDirective> directives) {
        return directives.stream()
                .filter(this::isDeprecatedDirective)
                .count() == 1;
    }

    private List<GraphQLDirective> addDeprecatedDirectiveIfNeeded(List<GraphQLDirective> directives) {
        if (!hasDeprecatedDirective(directives)) {
            directives = new ArrayList<>(directives);
            directives.add(DeprecatedDirective4Printing);
        }
        return directives;
    }

    private String directiveDefinitions(List<GraphQLDirective> directives) {
        StringBuilder sb = new StringBuilder();
        for (GraphQLDirective directive : directives) {
            sb.append(directiveDefinition(directive));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String directiveDefinition(GraphQLDirective directive) {
        StringBuilder sb = new StringBuilder();

        StringWriter sw = new StringWriter();
        printComments(new PrintWriter(sw), directive, "");

        sb.append(sw.toString());

        sb.append("directive @").append(directive.getName());

        GraphqlTypeComparatorEnvironment environment = GraphqlTypeComparatorEnvironment.newEnvironment()
                .parentType(GraphQLDirective.class)
                .elementType(GraphQLArgument.class)
                .build();
        Comparator<? super GraphQLSchemaElement> comparator = options.comparatorRegistry.getComparator(environment);

        List<GraphQLArgument> args = directive.getArguments();
        args = args
                .stream()
                .sorted(comparator)
                .collect(toList());

        sb.append(argsString(GraphQLDirective.class, args));

        sb.append(" on ");

        String locations = directive.validLocations().stream().map(Enum::name).collect(Collectors.joining(" | "));
        sb.append(locations);

        return sb.toString();
    }


    @SuppressWarnings("unchecked")
    private <T> TypePrinter<T> printer(Class<?> clazz) {
        TypePrinter typePrinter = printers.computeIfAbsent(clazz, k -> {
            Class<?> superClazz = clazz.getSuperclass();
            TypePrinter result;
            if (superClazz != Object.class) {
                result = printer(superClazz);
            } else {
                result = (out, type, visibility) -> out.println("Type not implemented : " + type);
            }
            return result;
        });
        return (TypePrinter<T>) typePrinter;
    }

    public String print(GraphQLType type) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        printType(out, type, DEFAULT_FIELD_VISIBILITY);

        return sw.toString();
    }

    @SuppressWarnings("unchecked")
    private void printType(PrintWriter out, List<GraphQLType> typesAsList, Class typeClazz, GraphqlFieldVisibility visibility) {
        typesAsList.stream()
                .filter(type -> typeClazz.isAssignableFrom(type.getClass()))
                .forEach(type -> printType(out, type, visibility));
    }

    private void printType(PrintWriter out, GraphQLType type, GraphqlFieldVisibility visibility) {
        TypePrinter<Object> printer = printer(type.getClass());
        printer.print(out, type, visibility);
    }

    private String printComments(Object graphQLType, String prefix) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        printComments(pw, graphQLType, prefix);
        return sw.toString();
    }

    private void printComments(PrintWriter out, Object graphQLType, String prefix) {

        String descriptionText = getDescription(graphQLType);
        if (isNullOrEmpty(descriptionText)) {
            return;
        }

        if (!isNullOrEmpty(descriptionText)) {
            List<String> lines = Arrays.asList(descriptionText.split("\n"));
            if (options.isDescriptionsAsHashComments()) {
                printMultiLineHashDescription(out, prefix, lines);
            } else {
                if (lines.size() > 1) {
                    printMultiLineDescription(out, prefix, lines);
                } else {
                    printSingleLineDescription(out, prefix, lines.get(0));
                }
            }
        }
    }

    private void printMultiLineHashDescription(PrintWriter out, String prefix, List<String> lines) {
        lines.forEach(l -> out.printf("%s#%s\n", prefix, l));
    }

    private void printMultiLineDescription(PrintWriter out, String prefix, List<String> lines) {
        out.printf("%s\"\"\"\n", prefix);
        lines.forEach(l -> out.printf("%s%s\n", prefix, l));
        out.printf("%s\"\"\"\n", prefix);
    }

    private void printSingleLineDescription(PrintWriter out, String prefix, String s) {
        out.printf("%s\"%s\"\n", prefix, s);
    }

    private boolean hasDescription(Object descriptionHolder) {
        String description = getDescription(descriptionHolder);
        return !isNullOrEmpty(description);
    }

    private String getDescription(Object descriptionHolder) {
        if (descriptionHolder instanceof GraphQLObjectType) {
            GraphQLObjectType type = (GraphQLObjectType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(ObjectTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLEnumType) {
            GraphQLEnumType type = (GraphQLEnumType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(EnumTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLFieldDefinition) {
            GraphQLFieldDefinition type = (GraphQLFieldDefinition) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(FieldDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLEnumValueDefinition) {
            GraphQLEnumValueDefinition type = (GraphQLEnumValueDefinition) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(EnumValueDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLUnionType) {
            GraphQLUnionType type = (GraphQLUnionType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(UnionTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLInputObjectType) {
            GraphQLInputObjectType type = (GraphQLInputObjectType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(InputObjectTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLInputObjectField) {
            GraphQLInputObjectField type = (GraphQLInputObjectField) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(InputValueDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLInterfaceType) {
            GraphQLInterfaceType type = (GraphQLInterfaceType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(InterfaceTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLScalarType) {
            GraphQLScalarType type = (GraphQLScalarType) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(ScalarTypeDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLArgument) {
            GraphQLArgument type = (GraphQLArgument) descriptionHolder;
            return description(type.getDescription(), ofNullable(type.getDefinition()).map(InputValueDefinition::getDescription).orElse(null));
        } else if (descriptionHolder instanceof GraphQLDirective) {
            GraphQLDirective type = (GraphQLDirective) descriptionHolder;
            return description(type.getDescription(), null);
        } else {
            return Assert.assertShouldNeverHappen();
        }
    }

    String description(String runtimeDescription, Description descriptionAst) {
        //
        // 95% of the time if the schema was built from SchemaGenerator then the runtime description is the only description
        // So the other code here is a really defensive way to get the description
        //
        String descriptionText = runtimeDescription;
        if (isNullOrEmpty(descriptionText)) {
            if (descriptionAst != null) {
                descriptionText = descriptionAst.getContent();
            }
        }
        return descriptionText;
    }

    private static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
