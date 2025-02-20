# PGAdapter and SQLAlchemy 1.4

PGAdapter has experimental support for [SQLAlchemy 1.4](https://docs.sqlalchemy.org/en/14/index.html)
with the `psycopg2` driver. This document shows how to use this sample application, and lists the
limitations when working with `SQLAlchemy` with PGAdapter.

It is __recommended to use SQLAlchemy 2.x in combination with psycopg 3.x__ instead of SQLAlchemy 1.4
with psycopg2. Psycopg2 never uses server-side query parameters. This will increase the latency of all
queries that are executed by SQLAlchemy. See [SQLAlchemy 2.x sample](../sqlalchemy2-sample/README.md)
for more information on how to use SQLAlchemy 2.x.

The [sample.py](sample.py) file contains a sample application using `SQLAlchemy` with PGAdapter. Use
this as a reference for features of `SQLAlchemy` that are supported with PGAdapter. This sample
assumes that the reader is familiar with `SQLAlchemy`, and it is not intended as a tutorial for how
to use `SQLAlchemy` in general.

See [Limitations](#limitations) for a full list of known limitations when working with `SQLAlchemy`.

## Start PGAdapter
You must start PGAdapter before you can run the sample. The following command shows how to start PGAdapter using the
pre-built Docker image. See [Running PGAdapter](../../../README.md#usage) for more information on other options for how
to run PGAdapter.

```shell
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json
docker pull gcr.io/cloud-spanner-pg-adapter/pgadapter
docker run \
  -d -p 5432:5432 \
  -v ${GOOGLE_APPLICATION_CREDENTIALS}:${GOOGLE_APPLICATION_CREDENTIALS}:ro \
  -e GOOGLE_APPLICATION_CREDENTIALS \
  -v /tmp:/tmp \
  gcr.io/cloud-spanner-pg-adapter/pgadapter \
  -p my-project -i my-instance \
  -x
```

## Creating the Sample Data Model
The sample data model contains example tables that cover all supported data types the Cloud Spanner
PostgreSQL dialect. It also includes an example for how [interleaved tables](https://cloud.google.com/spanner/docs/reference/postgresql/data-definition-language#extensions_to)
can be used with SQLAlchemy. Interleaved tables is a Cloud Spanner extension of the standard
PostgreSQL dialect.

The corresponding SQLAlchemy model is defined in [model.py](model.py).

Run the following command in this directory. Replace the host, port and database name with the actual
host, port and database name for your PGAdapter and database setup.

```shell
psql -h localhost -p 5432 -d my-database -f create_data_model.sql
```

You can also drop an existing data model using the `drop_data_model.sql` script:

```shell
psql -h localhost -p 5432 -d my-database -f drop_data_model.sql
```

## Data Types
Cloud Spanner supports the following data types in combination with `SQLAlchemy`.

| PostgreSQL Type                        | SQLAlchemy type         |
|----------------------------------------|-------------------------|
| boolean                                | Boolean                 |
| bigint / int8                          | Integer, BigInteger     |
| varchar                                | String                  |
| text                                   | String                  |
| float8 / double precision              | Float                   |
| numeric                                | Numeric                 |
| timestamptz / timestamp with time zone | DateTime(timezone=True) |
| date                                   | Date                    |
| bytea                                  | LargeBinary             |
| jsonb                                  | JSONB                   |


## Limitations
The following limitations are currently known:

| Limitation                   | Workaround                                                                                                                                                                                                                                                          |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Query Latency                | `psycopg2` never uses server-side query parameters. This increases the latency of all queries that are executed by SQLAlchemy. It is recommended to use SQLAlchemy 2.x instead if your application requires the lowest possible query latency.                      |
| Creating and Dropping Tables | Cloud Spanner does not support the full PostgreSQL DDL dialect. Automated creation of tables using `SQLAlchemy` is therefore not supported.                                                                                                                         |
| metadata.reflect()           | Cloud Spanner does not support all PostgreSQL `pg_catalog` tables. Using `metadata.reflect()` to get the current objects in the database is therefore not supported.                                                                                                |
| DDL Transactions             | Cloud Spanner does not support DDL statements in a transaction. Add `?options=-c spanner.ddl_transaction_mode=AutocommitExplicitTransaction` to your connection string to automatically convert DDL transactions to [non-atomic DDL batches](../../../docs/ddl.md). |
| Generated primary keys       | Manually assign a value to the primary key column in your code. The recommended primary key type is a random UUID. Sequences / SERIAL / IDENTITY columns are currently not supported.                                                                               |
| INSERT ... ON CONFLICT       | `INSERT ... ON CONFLICT` is not supported.                                                                                                                                                                                                                          |
| SAVEPOINT                    | Nested transactions and savepoints are not supported.                                                                                                                                                                                                               |
| SELECT ... FOR UPDATE        | `SELECT ... FOR UPDATE` is not supported.                                                                                                                                                                                                                           |
| Server side cursors          | Server side cursors are currently not supported.                                                                                                                                                                                                                    |
| Transaction isolation level  | Only SERIALIZABLE and AUTOCOMMIT are supported. `postgresql_readonly=True` is also supported. It is recommended to use either autocommit or read-only for workloads that only read data and/or that do not need to be atomic to get the best possible performance.  |
| Stored procedures            | Cloud Spanner does not support Stored Procedures.                                                                                                                                                                                                                   |
| User defined functions       | Cloud Spanner does not support User Defined Functions.                                                                                                                                                                                                              |
| Other drivers than psycopg2  | PGAdapter does not support using SQLAlchemy with any other drivers than `psycopg2`.                                                                                                                                                                                 |

### Generated Primary Keys
Generated primary keys are not supported and should be replaced with primary key definitions that
are manually assigned. See https://cloud.google.com/spanner/docs/schema-design#primary-key-prevent-hotspots
for more information on choosing a good primary key. This sample uses random UUIDs that are generated
by the client and stored as strings for primary keys.

```python
from uuid import uuid4

class Singer(Base):
  id = Column(String, primary_key=True)
  name = Column(String(100))

singer = Singer(
  id="{}".format(uuid4()),
  name="Alice")
```

### ON CONFLICT Clauses
`INSERT ... ON CONFLICT ...` are not supported by Cloud Spanner and should not be used. Trying to
use https://docs.sqlalchemy.org/en/14/dialects/postgresql.html#sqlalchemy.dialects.postgresql.Insert.on_conflict_do_update
or https://docs.sqlalchemy.org/en/14/dialects/postgresql.html#sqlalchemy.dialects.postgresql.Insert.on_conflict_do_nothing
will fail.

### SAVEPOINT - Nested transactions
`SAVEPOINT`s are not supported by Cloud Spanner. Nested transactions in SQLAlchemy are translated to
savepoints and are therefore not supported. Trying to use `Session.begin_nested()`
(https://docs.sqlalchemy.org/en/14/orm/session_api.html#sqlalchemy.orm.Session.begin_nested) will fail.

### Locking - SELECT ... FOR UPDATE
Locking clauses, like `SELECT ... FOR UPDATE`, are not supported (see also https://docs.sqlalchemy.org/en/20/orm/queryguide/query.html#sqlalchemy.orm.Query.with_for_update).
These are normally also not required, as Cloud Spanner uses isolation level `serializable` for
read/write transactions.

## Performance Considerations

### Parameterized Queries
`psycopg2` does not use [parameterized queries](https://cloud.google.com/spanner/docs/sql-best-practices#postgresql_1).
Instead, `psycopg2` will replace query parameters with literals in the SQL string before sending
these to PGAdapter. This means that each query must be parsed and planned separately. This will add
latency to queries that are executed multiple times compared to if the queries were executed using
actual query parameters.

It is therefore recommended to use `SQLAlchemy 2.x` in combination with `pscyopg 3.x` for applications
that require the lowest possible query latency. See [SQLAlchemy 2.x sample](../sqlalchemy2-sample)
for more information.

### Read-only Transactions
SQLAlchemy will by default use read/write transactions for all database operations, including for
workloads that only read data. This will cause Cloud Spanner to take locks for all data that is read
during the transaction. It is recommended to use either autocommit or [read-only transactions](https://cloud.google.com/spanner/docs/transactions#read-only_transactions)
for workloads that are known to only execute read operations. Read-only transactions do not take any
locks. You can create a separate database engine that can be used for read-only transactions from
your default database engine by adding the `postgresql_readonly=True` execution option.

```python
read_only_engine = engine.execution_options(postgresql_readonly=True)
```

### Autocommit
Using isolation level `AUTOCOMMIT` will suppress the use of (read/write) transactions for each
database operation in SQLAlchemy. Using autocommit is more efficient than read/write transactions
for workloads that only read and/or that do not need the atomicity that is offered by transactions.

You can create a separate database engine that can be used for workloads that do not need
transactions by adding the `isolation_level="AUTOCOMMIT"` execution option to your default database
engine.

```python
autocommit_engine = engine.execution_options(isolation_level="AUTOCOMMIT")
```

### Stale reads
Read-only transactions and database engines using `AUTOCOMMIT` will by default use strong reads for
queries. Cloud Spanner also supports stale reads.

* A strong read is a read at a current timestamp and is guaranteed to see all data that has been
  committed up until the start of this read. Spanner defaults to using strong reads to serve read requests.
* A stale read is read at a timestamp in the past. If your application is latency sensitive but
  tolerant of stale data, then stale reads can provide performance benefits.

See also https://cloud.google.com/spanner/docs/reads#read_types

You can create a database engine that will use stale reads in autocommit mode by adding the following
to the connection string and execution options of the engine:

```python
conn_string = "postgresql+psycopg2://user:password@localhost:5432/my-database" \
              "?options=-c spanner.read_only_staleness='MAX_STALENESS 10s'"
engine = create_engine(conn_string).execution_options(isolation_level="AUTOCOMMIT")
```
