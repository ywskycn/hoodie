---
title: Incremental Processing
keywords: incremental processing
sidebar: mydoc_sidebar
permalink: incremental_processing.html
toc: false
summary: In this page, we will discuss incremental processing primitives that Hoodie has to offer.
---

As discussed in the concepts section, the two basic primitives needed for [incrementally processing](https://www.oreilly.com/ideas/ubers-case-for-incremental-processing-on-hadoop),
data using Hoodie are `upserts` (to apply changes to a dataset) and `incremental pulls` (to obtain a change stream/log from a dataset). This section
discusses a few tools that can be used to achieve these on different contexts.

{% include callout.html content="Instructions are currently only for Copy-on-write storage. When merge-on-read storage is added, these tools would be revised to add that support" type="info" %}


## Upserts

Upserts can be used to apply a delta or an incremental change to a Hoodie dataset. For e.g, the incremental changes could be from a Kafka topic or files uploaded to HDFS or
even changes pulled from another Hoodie dataset. The `HoodieDeltaStreamer` utility provides the way to achieve all of these, by using the capabilities of `HoodieWriteClient`.

{% include callout.html content="Get involved in rewriting this tool [here](https://github.com/uber/hoodie/issues/20)" type="info" %}

The tool is a spark job (part of hoodie-utilities), that provides the following functionality

 - Ability to consume new events from Kafka, incremental imports from Sqoop or output of `HiveIncrementalPuller` or files under a folder on HDFS
 - Support json, avro or a custom payload types for the incoming data
 - New data is written to a Hoodie dataset, with support for checkpointing & schemas and registered onto Hive


## Incremental Pull

Hoodie datasets can be pulled incrementally, which means you can get ALL and ONLY the updated & new rows since a specified commit timestamp.
This, together with upserts, are particularly useful for building data pipelines where 1 or more source hoodie tables are incrementally pulled (streams/facts),
joined with other tables (datasets/dimensions), to produce deltas to a target hoodie dataset. Then, using the delta streamer tool these deltas can be upserted into the
target hoodie dataset to complete the pipeline.

#### Pulling through Hive

`HiveIncrementalPuller` allows the above to be done via HiveQL, combining the benefits of Hive (reliably process complex SQL queries) and incremental primitives
(speed up query by pulling tables incrementally instead of scanning fully). The tool uses Hive JDBC to run the Hive query saving its results in a temp table.
that can later be upserted. Upsert utility (`HoodieDeltaStreamer`) has all the state it needs from the directory structure to know what should be the commit time on the target table.
e.g: `/app/incremental-hql/intermediate/{source_table_name}_temp/{last_commit_included}`.The Delta Hive table registered will be of the form `{tmpdb}.{source_table}_{last_commit_included}`.

The following are the configuration options for HiveIncrementalPuller

| **Config** | **Description** | **Default** |
|hiveUrl| Hive Server 2 URL to connect to | jdbc:hive2://hadoophiveserver.com:10000/;transportMode=http;httpPath=hs2 |
|hiveUser| Hive Server 2 Username |  |
|hivePass| Hive Server 2 Password |  |
|queue| YARN Queue name |  |
|tmp| Directory where the temporary delta data is stored in HDFS. The directory structure will follow conventions. Please see the below section.  | /app/incremental-hql/intermediate |
|extractSQLFile| The SQL to execute on the source table to extract the data. The data extracted will be all the rows that changed since a particular point in time. |  |
|sourceTable| Source Table Name. Needed to set hive environment properties. |  |
|targetTable| Target Table Name. Needed for the intermediate storage directory structure.  |  |
|sourceDataPath| Source HDFS Base Path. This is where the hoodie metadata will be read. |  |
|targetDataPath| Target HDFS Base path. This is needed to compute the fromCommitTime. This is not needed if fromCommitTime is specified explicitly. |  |
|tmpdb| The database to which the intermediate temp delta table will be created | hoodie_temp |
|fromCommitTime| This is the most important parameter. This is the point in time from which the changed records are pulled from.  |  |
|maxCommits| Number of commits to include in the pull. Setting this to -1 will include all the commits from fromCommitTime. Setting this to a value > 0, will include records that ONLY changed in the specified number of commits after fromCommitTime. This may be needed if you need to catch up say 2 commits at a time. | 3 |
|help| Utility Help |  |


Setting the fromCommitTime=0 and maxCommits=-1 will pull in the entire source dataset and can be used to initiate backfills. If the target dataset is a hoodie dataset,
then the utility can determine if the target dataset has no commits or is behind more than 24 hour (this is configurable),
it will automatically use the backfill configuration, since applying the last 24 hours incrementally could take more time than doing a backfill. The current limitation of the tool
is the lack of support for self-joining the same table in mixed mode (normal and incremental modes).


#### Pulling through Spark

`HoodieReadClient` (inside hoodie-client) offers a more elegant way to pull data from Hoodie dataset (plus more) and process it via Spark.
This class can be used within existing Spark jobs and offers the following functionality.

| **API** | **Description** |
| listCommitsSince(),latestCommit() | Obtain commit times to pull data from |
| readSince(commitTime),readCommit(commitTime) | Provide the data from the commit time as a DataFrame, to process further on |
| read(keys) | Read out the data corresponding to the keys as a DataFrame, using Hoodie's own index for faster lookup |
| read(paths) | Read out the data under specified path, with the functionality of HoodieInputFormat. An alternative way to do SparkSQL on Hoodie datasets |
| filterExists() | Filter out already existing records from the provided RDD[HoodieRecord]. Useful for de-duplication |
| checkExists(keys) | Check if the provided keys exist in a Hoodie dataset |


## SQL Streamer

work in progress, tool being refactored out into open source Hoodie


{% include callout.html content="Get involved in building this tool [here](https://github.com/uber/hoodie/issues/20)" type="info" %}

