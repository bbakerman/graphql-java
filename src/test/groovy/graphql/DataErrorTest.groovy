package graphql

import graphql.execution.ExecutionPath
import graphql.language.SourceLocation
import spock.lang.Specification

class DataErrorTest extends Specification {

    def "test constructors"() {
        def error

        when:
        error = new DataError("Case1")

        then:
        error.message == "Case1"
        error.errorType == ErrorType.DataError
        error.extensions == [:]
        error.path == null
        error.locations == null


        when:
        error = new DataError("Case2", ExecutionPath.rootPath().segment("ABC"))

        then:
        error.message == "Case2"
        error.errorType == ErrorType.DataError
        error.extensions == [:]
        error.path == ["ABC"]
        error.locations == null

        when:
        error = new DataError("Case3", ExecutionPath.rootPath().segment("ABC"), new SourceLocation(1, 2), ["X": "Y"])

        then:
        error.message == "Case3"
        error.errorType == ErrorType.DataError
        error.extensions == ["X": "Y"]
        error.path == ["ABC"]
        error.locations == [new SourceLocation(1, 2)]

    }
}
