/*
 * Copyright (c) 2026, Potaty
 *
 * Identity and project ownership tables. These objects mirror the Flyway schema and are also
 * created in H2 so local/test databases exercise the same parent records as Postgres.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object WorkspacesTable : Table("workspaces") {
    val id = uuid("id")
    val name = text("name")
    val slug = text("slug").uniqueIndex("uq_workspaces_slug")
    val plan = text("plan")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object UsersTable : Table("users") {
    val id = uuid("id")
    val email = text("email").uniqueIndex("uq_users_email")
    val displayName = text("display_name").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WorkspaceMembersTable : Table("workspace_members") {
    val workspaceId = uuid("workspace_id")
    val userId = uuid("user_id")
    val role = text("role")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(workspaceId, userId)
}

object ProjectsTable : Table("projects") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val name = text("name")
    val slug = text("slug")
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_projects_workspace_slug", workspaceId, slug)
        uniqueIndex("uq_projects_workspace_id", workspaceId, id)
    }
}
