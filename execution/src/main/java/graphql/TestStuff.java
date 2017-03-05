package graphql;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@SuppressWarnings("Since15")
public class TestStuff {

    class ExecutionResult {
        String data;
        List<String> errors;
    }

    interface ExecutionStrategy<T> {
        T execute(GraphqlSchema schema);
    }

    class GraphqlSchema {

    }

    class SimpleExecutionStrategy implements ExecutionStrategy<ExecutionResult> {
        @Override
        public ExecutionResult execute(GraphqlSchema parameters) {
            return new ExecutionResult();
        }

    }

    class AysnchExecutionStrategy implements ExecutionStrategy<CompletionStage<ExecutionResult>> {
        @Override
        public CompletionStage<ExecutionResult> execute(GraphqlSchema parameters) {
            CompletableFuture<ExecutionResult> result = CompletableFuture.completedFuture(new ExecutionResult());
            return result;
        }

    }

    class GraphQL<T> {

        ExecutionStrategy<T> strategy;
        GraphqlSchema schema;

        public GraphQL(GraphqlSchema schema) {
            this.schema = schema;
        }

        GraphQL<T> withQueryStrategy(ExecutionStrategy<T> strategy) {
            this.strategy = strategy;
            return this;
        }

        T execute() {
            return strategy.execute(schema);
        }
    }

    public void main(String[] args) {


        GraphqlSchema schema = new GraphqlSchema();


        //
        // so you have to know your output type T here
        GraphQL<ExecutionResult> graphql1 = new GraphQL<>(schema);


        graphql1.withQueryStrategy(new SimpleExecutionStrategy());

        // but you get a specific result here
        ExecutionResult resultSimple = graphql1.execute();


        //
        // and again you have to know your output type T here
        GraphQL<CompletionStage<ExecutionResult>> graphql2 = new GraphQL<>(schema);


        graphql2.withQueryStrategy(new AysnchExecutionStrategy());

        // but you get a specific result here
        CompletionStage<ExecutionResult> result2 = graphql2.execute();
    }
}
