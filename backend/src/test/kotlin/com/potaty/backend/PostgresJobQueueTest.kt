/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.jobs.PostgresJobQueue
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PostgresJobQueueTest {

    @Test
    fun claimReclaimsRunningRowsAndTerminalSqlUsesLeaseFence() = runBlocking {
        val jobId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val dataSource = RecordingDataSource(jobId, workspaceId)
        val queue = PostgresJobQueue(dataSource)

        val claimed = queue.claim("worker-a", limit = 1, leaseSeconds = 60).single()

        val claimSql = dataSource.statements.first().sql.normalized()
        assertTrue(claimSql.contains("or status = 'running'"), claimSql)
        assertTrue(claimSql.contains("locked_until is null or locked_until <= now()"), claimSql)
        assertTrue(claimed.leaseOwner.startsWith("worker-a:"))
        assertEquals(claimed.leaseOwner, dataSource.statements.first().bindings[2])

        assertTrue(queue.complete(jobId, workspaceId, claimed.leaseOwner, """{"ok":true}"""))

        val complete = dataSource.statements.last()
        val completeSql = complete.sql.normalized()
        assertTrue(completeSql.contains("status = 'running'"), completeSql)
        assertTrue(completeSql.contains("locked_by = ?"), completeSql)
        assertTrue(completeSql.contains("locked_until > now()"), completeSql)
        assertEquals(claimed.leaseOwner, complete.bindings[4])
    }

    private fun String.normalized(): String = replace(Regex("""\s+"""), " ").trim().lowercase()

    private data class StatementCall(
        val sql: String,
        val bindings: MutableMap<Int, Any?> = linkedMapOf()
    )

    private class RecordingDataSource(
        private val jobId: UUID,
        private val workspaceId: UUID
    ) : DataSource {
        val statements = mutableListOf<StatementCall>()

        override fun getConnection(): Connection =
            proxy(Connection::class.java) { method, args ->
                when (method.name) {
                    "prepareStatement" -> {
                        val call = StatementCall(args!![0] as String)
                        statements += call
                        preparedStatement(call)
                    }
                    "getAutoCommit" -> false
                    "setAutoCommit",
                    "commit",
                    "rollback",
                    "close" -> Unit
                    else -> defaultValue(method.returnType)
                }
            }

        private fun preparedStatement(call: StatementCall): PreparedStatement =
            proxy(PreparedStatement::class.java) { method, args ->
                when {
                    method.name.startsWith("set") -> {
                        call.bindings[args!![0] as Int] = args[1]
                        Unit
                    }
                    method.name == "executeQuery" -> resultSet()
                    method.name == "executeUpdate" -> 1
                    method.name == "close" -> Unit
                    else -> defaultValue(method.returnType)
                }
            }

        private fun resultSet(): ResultSet {
            var beforeFirst = true
            return proxy(ResultSet::class.java) { method, args ->
                when (method.name) {
                    "next" ->
                        if (beforeFirst) {
                            beforeFirst = false
                            true
                        } else {
                            false
                        }
                    "getObject" ->
                        when (args!![0]) {
                            "id" -> jobId
                            "workspace_id" -> workspaceId
                            "project_id" -> null
                            else -> null
                        }
                    "getString" ->
                        when (args!![0]) {
                            "job_type" -> "postgres-test"
                            "input" -> "{}"
                            else -> null
                        }
                    "getInt" ->
                        when (args!![0]) {
                            "attempts" -> 2
                            "max_attempts" -> 3
                            else -> 0
                        }
                    "close" -> Unit
                    else -> defaultValue(method.returnType)
                }
            }
        }

        override fun getConnection(username: String?, password: String?): Connection = connection

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getGlobal()

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            interfaceType: Class<T>,
            handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?
        ): T {
            return Proxy.newProxyInstance(interfaceType.classLoader, arrayOf(interfaceType)) {
                    _,
                    method,
                    args ->
                handler(method, args)
            } as T
        }

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
