# PGAdapter - pgbench

[pgbench](https://www.postgresql.org/docs/current/pgbench.html) can be used with PGAdapter, but with
some limitations.

Follow these steps to initialize and run benchmarks with `pgbench` with PGAdapter:

## Create Data Model
The default data model that is generated by `pgbench` does not include primary keys for the tables.
Cloud Spanner requires all tables to have primary keys. Execute the following command to manually
create the data model for `pgbench`:

```shell
psql -h /tmp -p 5432 -d my-database \
        -c "START BATCH DDL;
            CREATE TABLE pgbench_accounts (
                aid integer primary key  NOT NULL,            
                bid integer  NULL,                
                abalance integer  NULL,           
                filler varchar(84)  NULL
            );
            CREATE TABLE pgbench_branches (
                bid integer primary key  NOT NULL,            
                bbalance integer  NULL,           
                filler varchar(88)  NULL
            );
            CREATE TABLE pgbench_history (       
                tid integer  NOT NULL DEFAULT -1,                      
                bid integer  NOT NULL DEFAULT -1,                      
                aid integer  NOT NULL DEFAULT -1,                      
                delta integer  NULL,                    
                mtime timestamptz  NULL,
                filler varchar(22)  NULL,
                primary key (tid, bid, aid)
            );
            CREATE TABLE pgbench_tellers (
                tid integer  primary key NOT NULL,           
                bid integer  NULL,               
                tbalance integer  NULL,          
                filler varchar(84)  NULL
            );
            RUN BATCH;"
```

## Initialize Data
`pgbench` deletes and inserts data into PostgreSQL using a combination of `truncate`, `insert` and
`copy` statements. These statements all run in a single transaction. The amount of data that is
modified during this transaction will exceed the transaction mutation limits of Cloud Spanner. This
can be worked around by adding the following options to the `pgbench` initialization command:

```shell
pgbench "host=/tmp port=5432 dbname=my-database \
        options='-c spanner.force_autocommit=on -c spanner.autocommit_dml_mode=\'partitioned_non_atomic\''" \
        -i -Ig \
        --scale=100
```

These additional options do the following:
1. `spanner.force_autocommit=true`: This instructs PGAdapter to ignore any transaction statements and
   execute all statements in autocommit mode. This prevents the initialization from being executed as
   a single, large transaction.
2. `spanner.autocommit_dml_mode='partitioned_non_atomic'`: This instructs PGAdapter to use Partitioned
   DML for (large) update statements. This ensures that a single statement succeeds even if it would
   exceed the transaction limits of Cloud Spanner, including large `copy` operations.
3. `-i` activates initialization mode of `pgbench`.
4. `-Ig` instructs `pgbench` to generate test data client side. PGAdapter does not support generating
   test data server side.

## Running Benchmarks
You can run different benchmarks after finishing the steps above.

### Default Benchmark
Run a default benchmark to verify that everything works as expected.

```shell
pgbench "host=/tmp port=5432 dbname=my-database"
```

### Number of Clients
Increase the number of clients and threads to increase the number of parallel transactions.

```shell
pgbench "host=/tmp port=5432 dbname=my-database" \
        --client=100 --jobs=100 \
        --progress=10
```

## Dropping Tables
Execute the following command to remove the `pgbench` tables from your database if you no longer
need them.

```shell
psql -h /tmp -p 5432 -d my-database \
        -c "START BATCH DDL;
            DROP TABLE pgbench_history;
            DROP TABLE pgbench_tellers;
            DROP TABLE pgbench_branches;
            DROP TABLE pgbench_accounts;
            RUN BATCH;"
```
