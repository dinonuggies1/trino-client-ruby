====================
Prometheus Connector
====================

The Prometheus connector allows reading
`Prometheus <https://prometheus.io/>`_
metrics as tables in Trino.

The mechanism for querying Prometheus is to use the Prometheus HTTP API. Specifically, all queries are resolved to Prometheus Instant queries
with a form like: http://localhost:9090/api/v1/query?query=up[21d]&time=1568229904.000.
In this case the ``up`` metric is taken from the Trino query table name, ``21d`` is the duration of the query. The Prometheus ``time`` value
corresponds to the ``timestamp`` field. Trino queries are translated from their use of the ``timestamp`` field to a duration and time value
as needed. Trino splits are generated by dividing the query range into attempted equal chunks.

Configuration
-------------

Create ``etc/catalog/prometheus.properties``
to mount the Prometheus connector as the ``prometheus`` catalog,
replacing the properties as appropriate:

.. code-block:: text

    connector.name=prometheus
    prometheus.uri=http://localhost:9090
    prometheus.query.chunk.size.duration=1d
    prometheus.max.query.range.duration=21d
    prometheus.cache.ttl=30s
    prometheus.bearer.token.file=/path/to/bearer/token/file

Configuration Properties
------------------------

The following configuration properties are available:

======================================== ============================================================================================
Property Name                                   Description
======================================== ============================================================================================
``prometheus.uri``                       Where to find Prometheus coordinator host
``prometheus.query.chunk.size.duration`` The duration of each query to Prometheus
``prometheus.max.query.range.duration``  Width of overall query to Prometheus, will be divided into query-chunk-size-duration queries
``prometheus.cache.ttl``                 How long values from this config file are cached
``prometheus.bearer.token.file``         File holding bearer token if needed for access to Prometheus
======================================== ============================================================================================

Not Exhausting Your Trino Available Heap
-----------------------------------------

The ``prometheus.query.chunk.size.duration`` and ``prometheus.max.query.range.duration`` are values to protect Trino from
too much data coming back from Prometheus. The ``prometheus.max.query.range.duration`` is the item of
particular interest.

On a Prometheus instance that has been running for awhile and depending
on data retention settings, ``21d`` might be far too much. Perhaps ``1h`` might be a more reasonable setting.
In the case of ``1h`` it might be then useful to set ``prometheus.query.chunk.size.duration`` to ``10m``, dividing the
query window into 6 queries each of which can be handled in a Trino split.

Primarily query issuers can limit the amount of data returned by Prometheus by taking
advantage of ``WHERE`` clause limits on ``timestamp``, setting an upper bound and lower bound that define
a relatively small window. For instance:

.. code-block:: sql

    SELECT * FROM prometheus.default.up WHERE timestamp > (NOW() - INTERVAL '10' second);

If the query does not include a WHERE clause limit, these config
settings are meant to protect against an unlimited query.


Bearer Token Authentication
---------------------------

Prometheus can be setup to require a Authorization header with every query. The value in
``prometheus.bearer.token.file`` allows for a bearer token to be read from the configured file. This file
is optional and not required unless your Prometheus setup requires it.
