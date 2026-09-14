package ru.hollowhorizon.hollowengine.common.scripting.annotations

import kotlin.reflect.KClass

/**
 * Pulls other scripts into this one. A plain name is resolved next to the importing script, inside its
 * own namespace; a qualified `addon-id:path/lib.kts` reaches into another namespace and requires that
 * namespace to be declared in `dependsOn`.
 */
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
annotation class Import(vararg val file: String)

/**
 * Reuses this script's instance when it is reached more than once through an import graph.
 * Unannotated imported scripts are evaluated independently for every import path.
 */
@Target(AnnotationTarget.FILE)
annotation class SharedScript

/**
 * Attaches a `*.node.kts` script to a host type beyond the default [net.minecraft.server.MinecraftServer].
 *
 * `@file:Attach(net.minecraft.world.entity.LivingEntity::class)` adds [value] as an implicit receiver, so the
 * script body can call members of the bound entity directly and the entity-specific handlers
 * (`onInteract`, `onHurt`, `onDie`, ...) become available. Such a node must be started via
 * `/he scripting attach <entity> ...`.
 *
 * [value] is the host class.
 */
@Target(AnnotationTarget.FILE)
annotation class Attach(val value: KClass<*>)

/**
 * Makes a `*.reload.kts` a client script: it runs only on the physical client, is restarted by every
 * client resource reload (F3+T, a resource pack change) and gets `minecraft` instead of `recipeManager`.
 * A dedicated server does not even compile it.
 */
@Target(AnnotationTarget.FILE)
annotation class ClientSide

/**
 * Makes a `*.reload.kts` a server script, which it is without any annotation: it runs on the logical server
 * and is restarted by every datapack load, the opening of a world and `/reload`.
 */
@Target(AnnotationTarget.FILE)
annotation class ServerSide

@Target(AnnotationTarget.FUNCTION)
annotation class State(val name: String = "")
