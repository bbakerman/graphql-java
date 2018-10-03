package graphql;

import org.dataloader.DataLoaderRegistry;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static graphql.Assert.assertNotNull;

/**
 * This represents the series of values that can be input on a graphql query execution
 */
@PublicApi
public class ExecutionInput {
    private final String query;
    private final String operationName;
    private final Object context;
    private final Object root;
    private final Map<String, Object> variables;
    private final DataLoaderRegistry dataLoaderRegistry;


    public ExecutionInput(String query, String operationName, Object context, Object root, Map<String, Object> variables) {
        this(query,operationName,context,root,variables, new DataLoaderRegistry());
    }

    @Internal
    private  ExecutionInput(String query, String operationName, Object context, Object root, Map<String, Object> variables, DataLoaderRegistry dataLoaderRegistry) {
        this.query = query;
        this.operationName = operationName;
        this.context = context;
        this.root = root;
        this.variables = variables;
        this.dataLoaderRegistry = dataLoaderRegistry;
    }

    /**
     * @return the query text
     */
    public String getQuery() {
        return query;
    }

    /**
     * @return the name of the query operation
     */
    public String getOperationName() {
        return operationName;
    }

    /**
     * @return the context object to pass to all data fetchers
     */
    public Object getContext() {
        return context;
    }

    /**
     * @return the root object to start the query execution on
     */
    public Object getRoot() {
        return root;
    }

    /**
     * @return a map of variables that can be referenced via $syntax in the query
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * @return the data loader registry associated with this execution
     */
    public DataLoaderRegistry getDataLoaderRegistry() {
        return dataLoaderRegistry;
    }

    /**
     * This helps you transform the current ExecutionInput object into another one by starting a builder with all
     * the current values and allows you to transform it how you want.
     *
     * @param builderConsumer the consumer code that will be given a builder to transform
     *
     * @return a new ExecutionInput object based on calling build on that builder
     */
    public ExecutionInput transform(Consumer<Builder> builderConsumer) {
        Builder builder = new Builder()
                .query(this.query)
                .operationName(this.operationName)
                .context(this.context)
                .root(this.root)
                .variables(this.variables);

        builderConsumer.accept(builder);

        return builder.build();
    }


    @Override
    public String toString() {
        return "ExecutionInput{" +
                "query='" + query + '\'' +
                ", operationName='" + operationName + '\'' +
                ", context=" + context +
                ", root=" + root +
                ", variables=" + variables +
                ", dataLoaderRegistry=" + dataLoaderRegistry +
                '}';
    }

    /**
     * @return a new builder of ExecutionInput objects
     */
    public static Builder newExecutionInput() {
        return new Builder();
    }

    public static class Builder {

        private String query;
        private String operationName;
        private Object context;
        private Object root;
        private Map<String, Object> variables = Collections.emptyMap();
        private DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder operationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public Builder context(Object context) {
            this.context = context;
            return this;
        }

        public Builder root(Object root) {
            this.root = root;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public Builder dataLoaderRegistry(DataLoaderRegistry dataLoaderRegistry) {
            this.dataLoaderRegistry = assertNotNull(dataLoaderRegistry);
            return this;
        }

        public ExecutionInput build() {
            return new ExecutionInput(query, operationName, context, root, variables, dataLoaderRegistry);
        }
    }
}
