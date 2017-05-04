package graphql

import spock.lang.Specification

class AssertTest extends Specification {
    def "AssertNotNull on null"() {
        when:
        Assert.assertNotNull(null, "ErrorMessage")
        then:
        def e = thrown(AssertException)
        e.message == "ErrorMessage"
    }

    def "AssertNotNull on non null"() {
        when:
        def notNull = Assert.assertNotNull("NotNull", "ErrorMessage")
        then:
        noExceptionThrown()
        notNull == "NotNull"
    }

    def "AssertState when false"() {
        when:
        Assert.assertState(false, "ErrorMessage")
        then:
        def e = thrown(AssertException)
        e.message == "ErrorMessage"
    }

    def "AssertState when true"() {
        when:
        Assert.assertState(true, "ErrorMessage")
        then:
        noExceptionThrown()
    }

    def "AssertNotEmpty is false"() {
        when:
        Assert.assertNotEmpty(Collections.emptyList(), "ErrorMessage")
        then:
        def e = thrown(AssertException)
        e.message == "ErrorMessage"
    }

    def "AssertNotEmpty is true"() {
        when:
        Collection result = Assert.assertNotEmpty(Collections.singleton("NotEmpty"), "ErrorMessage")
        then:
        result.size() == 1
    }

    def "AssertValidName when invalid"() {
        when:
        Assert.assertValidName("namesCantHave/CharactersInThem")
        then:
        thrown(AssertException)
    }

    def "AssertValidName when valid"() {
        when:
        Assert.assertValidName("namesCanBeLikeThis")
        then:
        noExceptionThrown()
    }
}
