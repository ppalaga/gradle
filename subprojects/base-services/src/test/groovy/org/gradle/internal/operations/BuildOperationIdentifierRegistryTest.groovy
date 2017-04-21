package org.gradle.internal.operations

import spock.lang.Specification
import spock.lang.Subject

@Subject(BuildOperationIdentifierRegistry)
class BuildOperationIdentifierRegistryTest extends Specification {
    def "able to set and retrieve current operation across threads"() {
        expect:
        BuildOperationIdentifierRegistry.getCurrentOperationIdentifier() == null

        when:
        BuildOperationIdentifierRegistry.setCurrentOperationIdentifier(new OperationIdentifier(1L))

        then:
        BuildOperationIdentifierRegistry.getCurrentOperationIdentifier().id == 1L

        when:
        BuildOperationIdentifierRegistry.clearCurrentOperationIdentifier()

        then:
        BuildOperationIdentifierRegistry.getCurrentOperationIdentifier() == null
    }
}
