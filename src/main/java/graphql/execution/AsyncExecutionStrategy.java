package graphql.execution;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.Field;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The standard graphql execution strategy that runs fields in serial order
 */
public class AsyncExecutionStrategy extends ExecutionStrategy {

    /**
     * The standard graphql execution strategy that runs fields in serial order
     */
    public AsyncExecutionStrategy() {
        super(new SimpleDataFetcherExceptionHandler());
    }

    /**
     * Creates a simple execution handler that uses the provided exception handler
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
        Map<String, Object> resolvedValuesByField = new LinkedHashMap<>();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .whenComplete((notUsed1, notUsed2) -> {
                    int ix = 0;
                    for (CompletableFuture<ExecutionResult> future : futures) {

                        if (future.isCompletedExceptionally()) {
                            future.whenComplete((Null, e) -> {

                                if (e instanceof CompletionException && e.getCause() instanceof NonNullableFieldWasNullException) {
                                    NonNullableFieldWasNullException nonNullableException = (NonNullableFieldWasNullException) e.getCause();
                                    ExecutionTypeInfo typeInfo = nonNullableException.getTypeInfo();
                                    if (typeInfo.hasParentType() && typeInfo.getParentTypeInfo().isNonNullType()) {
                                        result.completeExceptionally(new NonNullableFieldWasNullException(nonNullableException));
                                    } else {
                                        result.complete(new ExecutionResultImpl(null, executionContext.getErrors()));
                                    }
                                } else {
                                    result.completeExceptionally(e);
                                }
                            });
                            return;
                        }
                        String fieldName = fieldNames.get(ix++);
                        ExecutionResult resolvedResult = future.join();
                        resolvedValuesByField.put(fieldName, resolvedResult != null ? resolvedResult.getData() : null);
                    }
                    result.complete(new ExecutionResultImpl(resolvedValuesByField, executionContext.getErrors()));
                });

        return result;
    }
}
