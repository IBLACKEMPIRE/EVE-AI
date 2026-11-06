// Minimal reflective runner for tools/test-core.sh. Runs every @org.junit.Test method in the
// given classes and exits non-zero on any failure.
package eve.testrunner

import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var pass = 0
    val failures = mutableListOf<String>()
    for (cn in args) {
        val cls = Class.forName(cn)
        for (m in cls.declaredMethods.sortedBy { it.name }) {
            if (!m.isAnnotationPresent(org.junit.Test::class.java)) continue
            val label = "${cls.simpleName}.${m.name}"
            val start = System.nanoTime()
            try {
                m.invoke(cls.getDeclaredConstructor().newInstance())
                pass++
                println("PASS  $label (${(System.nanoTime() - start) / 1_000_000} ms)")
            } catch (e: InvocationTargetException) {
                failures += "$label: ${e.targetException}"
                println("FAIL  $label: ${e.targetException}")
            }
        }
    }
    println("\n$pass passed, ${failures.size} failed")
    if (failures.isNotEmpty()) exitProcess(1)
}
