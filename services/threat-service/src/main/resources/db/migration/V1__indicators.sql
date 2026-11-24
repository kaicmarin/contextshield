create table threat_indicator (
    id varchar(40) primary key,
    organization_id bigint not null,
    kind varchar(40) not null,
    value_hash varchar(64) not null,
    display varchar(120) not null,
    status varchar(20) not null,
    visibility varchar(20) not null,
    training_label boolean not null,
    note varchar(500),
    created_at timestamp not null,
    updated_at timestamp not null
);

create index ix_threat_org on threat_indicator (organization_id, status);
create index ix_threat_shared on threat_indicator (visibility, status, value_hash);
