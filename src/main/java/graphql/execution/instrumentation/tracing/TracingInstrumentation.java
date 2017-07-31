package graphql.execution.instrumentation.tracing;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.PublicApi;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.NoOpInstrumentation.NoOpInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationDataFetchParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.language.Document;
import graphql.validation.ValidationError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This {@link Instrumentation} implementation uses {@link TracingSupport} to
 * capture tracing information and puts it into the {@link ExecutionResult}
 */
@PublicApi
public class TracingInstrumentation implements Instrumentation {

    private TracingSupport tracingSupport;
    private Map<String, Object> tracingData;

    @Override
    public ExecutionResult instrumentExecutionResult(ExecutionResult executionResult) {
        Map<Object, Object> tracingMap = new LinkedHashMap<>();
        tracingMap.put("tracing", tracingData);
        return new ExecutionResultImpl(executionResult.getData(), executionResult.getErrors(), tracingMap);
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(InstrumentationExecutionParameters parameters) {
        tracingSupport = new TracingSupport();
        return new InstrumentationContext<ExecutionResult>() {
            @Override
            public void onEnd(ExecutionResult result) {
                tracingData = tracingSupport.snapshotTracingData();
            }

            @Override
            public void onEnd(Exception e) {
                tracingData = tracingSupport.snapshotTracingData();
            }
        };
    }

    @Override
    public InstrumentationContext<Object> beginFieldFetch(InstrumentationFieldFetchParameters parameters) {
        TracingSupport.TracingContext ctx = tracingSupport.beginField(parameters.getEnvironment());
        return new InstrumentationContext<Object>() {
            @Override
            public void onEnd(Object result) {
                ctx.onEnd();
            }

            @Override
            public void onEnd(Exception e) {
                ctx.onEnd();
            }
        };
    }

    @Override
    public InstrumentationContext<Document> beginParse(InstrumentationExecutionParameters parameters) {
        return new NoOpInstrumentationContext<>();
    }

    @Override
    public InstrumentationContext<List<ValidationError>> beginValidation(InstrumentationValidationParameters parameters) {
        return new NoOpInstrumentationContext<>();
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginDataFetch(InstrumentationDataFetchParameters parameters) {
        return new NoOpInstrumentationContext<>();
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginField(InstrumentationFieldParameters parameters) {
        return new NoOpInstrumentationContext<>();
    }
}
