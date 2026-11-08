package android.os

/**
 * JVM fixture with the platform's exact shape: the real
 * `android.os.NetworkOnMainThreadException` carries NO message, which is what made
 * the pass-37 probe failure unclassifiable (F-37-A). core/transport is a pure-JVM
 * module, so the framework class is not on this test classpath.
 */
class NetworkOnMainThreadException : RuntimeException()
