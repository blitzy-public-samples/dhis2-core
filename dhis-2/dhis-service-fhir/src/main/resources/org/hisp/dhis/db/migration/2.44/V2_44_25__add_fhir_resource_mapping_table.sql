create table if not exists fhirresourcemapping (
    fhirresourcemappingid int8 not null,
    uid varchar(11) not null,
    code varchar(50) null,
    created timestamp not null,
    lastupdated timestamp not null,
    lastupdatedby int8 null,
    name varchar(230) not null,
    userid int8 null,
    translations jsonb null,
    sharing jsonb null default '{}'::jsonb,
    attributevalues jsonb null default '{}'::jsonb,
    resourcetype varchar(50) not null,
    trackedentitytypeid int8 not null,
    programid int8 null,
    programstageid int8 null,
    fieldmappings jsonb not null default '[]'::jsonb,
    constraint fhirresourcemapping_pkey primary key (fhirresourcemappingid),
    constraint fhirresourcemapping_uid_key unique (uid),
    constraint fhirresourcemapping_code_key unique (code),
    constraint fhirresourcemapping_name_key unique (name),
    constraint fk_lastupdateby_userid foreign key (lastupdatedby) references userinfo(userinfoid),
    constraint fk_fhirresourcemapping_userid foreign key (userid) references userinfo(userinfoid),
    constraint fk_fhirresourcemapping_trackedentitytypeid foreign key (trackedentitytypeid) references trackedentitytype(trackedentitytypeid),
    constraint fk_fhirresourcemapping_programid foreign key (programid) references program(programid),
    constraint fk_fhirresourcemapping_programstageid foreign key (programstageid) references programstage(programstageid)
);

create unique index if not exists ux_fhirresourcemapping_patient
    on fhirresourcemapping (resourcetype)
    where resourcetype = 'PATIENT';

create unique index if not exists ux_fhirresourcemapping_stage
    on fhirresourcemapping (resourcetype, programstageid)
    where resourcetype in ('ENCOUNTER', 'OBSERVATION');

create unique index if not exists ux_fhirresourcemapping_immunization
    on fhirresourcemapping (programstageid, (jsonb_path_query_first(fieldmappings, '$[*] ? (@.target == "IMMUNIZATION_ADMINISTERED").source') #>> '{}'))
    where resourcetype = 'IMMUNIZATION';
