package graphql.execution;

import graphql.ExecutionResult;
import graphql.language.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The standard graphql execution strategy that runs fields asynchronously non-blocking.
 */
public class AsyncExecutionStrategy extends AbstractAsyncExecutionStrategy {

    /**
     * The standard graphql execution strategy that runs fields asynchronously
     */
    public AsyncExecutionStrategy() {
        super(new SimpleDataFetcherExceptionHandler());
    }

    /**
     * Creates a execution strategy that uses the provided exception handler
     *
     * @param exceptionHandler the exception handler to use
     */
    public AsyncExecutionStrategy(DataFetcherExceptionHandler exceptionHandler) {
        super(exceptionHandler);
    }

    @Override
    public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext, ExecutionStrategyParameters parameters) throws NonNullableFieldWasNullException {

        Map<String, List<Field>> fields = parameters.fields();
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        List<CompletableFuture<ExecutionResult>> futures = new ArrayList<>();
        for (String fieldName : fieldNames) {
            List<Field> currentField = fields.get(fieldName);

            ExecutionPath fieldPath = parameters.path().segment(fieldName);
            ExecutionStrategyParameters newParameters = parameters
                    .transform(builder -> builder.field(currentField).path(fieldPath));

            CompletableFuture<ExecutionResult> future = resolveField(executionContext, newParameters);
            futures.add(future);
        }

        CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .whenComplete(futuresCompleted(executionContext, fieldNames, futures, result));

        return result;
    }

    private BiConsumer<Void, Throwable> futuresCompleted(ExecutionContext executionContext,
                                                         List<String> fieldNames,
                                                         List<CompletableFuture<ExecutionResult>> futures,
                                                         CompletableFuture<ExecutionResult> result) {
        return (notUsed1, notUsed2) -> {
            completeCompletableFuture(executionContext, fieldNames, futures, result);
        };
    }


}
