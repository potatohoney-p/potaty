-- Copyright (c) 2026, Potaty
-- V3: align the original extraction DDL with the Exposed persistence model and enforce that
-- tenant-owned children can only reference parents in the same workspace.

-- ---------- extraction column contract ----------

alter table extracted_entities rename column canonical_key to entity_key;
alter table extracted_entities rename column label to name;
alter table extracted_entities rename column entity_type to type;
alter table extracted_entities add column canonical_name text;
update extracted_entities set canonical_name = entity_key where canonical_name is null;
alter table extracted_entities alter column canonical_name set not null;
alter table extracted_entities alter column source_version_id drop not null;
alter table extracted_entities add column evidence_chunk_ids jsonb not null default '[]'::jsonb;
alter table extracted_entities drop column extraction_source;

alter table extracted_relations rename column relation_type to type;
alter table extracted_relations alter column source_version_id drop not null;
alter table extracted_relations add column evidence_chunk_ids jsonb not null default '[]'::jsonb;
alter table extracted_relations drop column extraction_source;

-- ---------- composite tenant ownership keys ----------

alter table projects add constraint uq_projects_workspace_id unique (workspace_id, id);
alter table sources add constraint uq_sources_workspace_id unique (workspace_id, id);
alter table source_versions add constraint uq_source_versions_workspace_id unique (workspace_id, id);
alter table diagrams add constraint uq_diagrams_workspace_id unique (workspace_id, id);
alter table diagram_versions add constraint uq_diagram_versions_workspace_id unique (workspace_id, id);
alter table jobs add constraint uq_jobs_workspace_id unique (workspace_id, id);

-- ---------- replace cross-workspace-capable foreign keys ----------

alter table sources drop constraint if exists sources_project_id_fkey;
alter table sources add constraint fk_sources_workspace_project
    foreign key (workspace_id, project_id) references projects(workspace_id, id);

alter table source_versions drop constraint if exists source_versions_source_id_fkey;
alter table source_versions add constraint fk_source_versions_workspace_source
    foreign key (workspace_id, source_id) references sources(workspace_id, id);

alter table source_chunks drop constraint if exists source_chunks_source_version_id_fkey;
alter table source_chunks add constraint fk_source_chunks_workspace_version
    foreign key (workspace_id, source_version_id) references source_versions(workspace_id, id);

alter table extracted_entities drop constraint if exists extracted_entities_project_id_fkey;
alter table extracted_entities drop constraint if exists extracted_entities_source_version_id_fkey;
alter table extracted_entities add constraint fk_extracted_entities_workspace_project
    foreign key (workspace_id, project_id) references projects(workspace_id, id);
alter table extracted_entities add constraint fk_extracted_entities_workspace_version
    foreign key (workspace_id, source_version_id) references source_versions(workspace_id, id);

alter table extracted_relations drop constraint if exists extracted_relations_project_id_fkey;
alter table extracted_relations drop constraint if exists extracted_relations_source_version_id_fkey;
alter table extracted_relations add constraint fk_extracted_relations_workspace_project
    foreign key (workspace_id, project_id) references projects(workspace_id, id);
alter table extracted_relations add constraint fk_extracted_relations_workspace_version
    foreign key (workspace_id, source_version_id) references source_versions(workspace_id, id);

alter table diagrams drop constraint if exists diagrams_project_id_fkey;
alter table diagrams add constraint fk_diagrams_workspace_project
    foreign key (workspace_id, project_id) references projects(workspace_id, id);

alter table diagram_versions drop constraint if exists diagram_versions_diagram_id_fkey;
alter table diagram_versions add constraint fk_diagram_versions_workspace_diagram
    foreign key (workspace_id, diagram_id) references diagrams(workspace_id, id);

alter table renderings drop constraint if exists renderings_diagram_version_id_fkey;
alter table renderings add constraint fk_renderings_workspace_version
    foreign key (workspace_id, diagram_version_id) references diagram_versions(workspace_id, id);

alter table jobs drop constraint if exists jobs_project_id_fkey;
alter table jobs add constraint fk_jobs_workspace_project
    foreign key (workspace_id, project_id) references projects(workspace_id, id);

alter table job_events drop constraint if exists job_events_job_id_fkey;
alter table job_events add constraint fk_job_events_workspace_job
    foreign key (workspace_id, job_id) references jobs(workspace_id, id);

alter table usage_events drop constraint if exists usage_events_job_id_fkey;
alter table usage_events add constraint fk_usage_events_workspace_job
    foreign key (workspace_id, job_id) references jobs(workspace_id, id);
