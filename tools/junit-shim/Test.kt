// Local-only stand-in for org.junit.Test, used by tools/test-core.sh when JUnit can't be
// downloaded. Gradle builds use the real JUnit 4 (see core/build.gradle.kts).
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
