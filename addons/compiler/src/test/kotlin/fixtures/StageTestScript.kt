package ru.hollowhorizon.hollowengine.test

/**
 * The base of the scripts stage tests compile.
 *
 * It lives in a package of its own, as the bases of game scripts do: Kotlin 2.4 looks the base class up while it
 * builds a script, before the classes of the script are known, so a script in the package of its base cannot name
 * its own classes in signatures.
 */
abstract class StageTestScript
