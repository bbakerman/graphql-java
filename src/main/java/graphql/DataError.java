package graphql;


import graphql.execution.ExecutionPath;
import graphql.language.SourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This graphql error can be used to indicate a general error in fetched data.  You could return it from a data fetcher
 * to indicate that not only should the field be null but there is an error message associated with that field fetch, giving
 * your downstream clients more information about the field and why it has a null value.
 */
@PublicApi
public class DataError implements GraphQLError {

    private final String message;
    private final List<Object> path;
    private final List<SourceLocation> locations;
    private final Map<String, Object> extensions;

    public DataError(String message) {
        this(message, null, null, Collections.emptyMap());
    }

    public DataError(String message, ExecutionPath path) {
        this(message, path, null, Collections.emptyMap());
    }

    public DataError(String message, ExecutionPath path, SourceLocation sourceLocation, Map<String, Object> extensions) {
        this.path = path == null ? null : path.toList();
        this.locations = sourceLocation == null ? null : Collections.singletonList(sourceLocation);
        this.extensions = new LinkedHashMap<>();
        this.extensions.putAll(extensions);
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return locations;
    }

    public List<Object> getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.DataError;
    }

    @Override
    public String toString() {
        return "DataError {" +
                " message=" + message +
                " path=" + path +
                " locations=" + locations +
                '}';
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object o) {
        return GraphqlErrorHelper.equals(this, o);
    }

    @Override
    public int hashCode() {
        return GraphqlErrorHelper.hashCode(this);
    }
}
