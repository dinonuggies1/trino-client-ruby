=============================
Secure Internal Communication
=============================

The Trino cluster can be configured to use secured communication. Communication
between Trino nodes can be secured with SSL/TLS.

Internal Authentication
-----------------------

Requests between Trino nodes are authenticated using a shared secret. For secure
internal communication, the shared secret must be configured on all nodes in the cluster:

.. code-block:: text

    internal-communication.shared-secret=<secret>

A large random key is recommended, and can be generated with the following Linux
command:

.. code-block:: text

    openssl rand 512 | base64

Internal SSL/TLS configuration
------------------------------

SSL/TLS is configured in the ``config.properties`` file.  The SSL/TLS on the
worker and coordinator nodes are configured using the same set of properties.
Every node in the cluster must be configured. Nodes that have not been
configured, or are configured incorrectly, are not able to communicate with
other nodes in the cluster.

To enable SSL/TLS for Trino internal communication, do the following:

1. Disable HTTP endpoint.

   .. code-block:: text

       http-server.http.enabled=false

   .. warning::

       You can enable HTTPS, while leaving HTTP enabled. In most cases this is a
       security hole. If you are certain you want to use this configuration, you
       should consider using an firewall to limit access to the HTTP endpoint to
       only those hosts that should be allowed to use it.

2. Configure the cluster to communicate using the fully qualified domain name (fqdn)
   of the cluster nodes. This can be done in either of the following ways:

   - If the DNS service is configured properly, we can just let the nodes
     introduce themselves to the coordinator using the hostname taken from
     the system configuration (``hostname --fqdn``)

     .. code-block:: text

         node.internal-address-source=FQDN

   - It is also possible to specify each node's fully-qualified hostname manually.
     This is different for every host. Hosts should be in the same domain to
     make it easy to create the correct SSL/TLS certificates.
     e.g.: ``coordinator.example.com``, ``worker1.example.com``, ``worker2.example.com``.

     .. code-block:: text

         node.internal-address=<node fqdn>


3. Generate a Java Keystore File. Every Trino node must be able to connect to
   any other node within the same cluster. It is possible to create unique
   certificates for every node using the fully-qualified hostname of each host,
   create a keystore that contains all the public keys for all of the hosts,
   and specify it for the client (see step #8 below). In most cases it is
   simpler to use a wildcard in the certificate as shown below.

   .. code-block:: text

       keytool -genkeypair -alias example.com -keyalg RSA -keystore keystore.jks
       Enter keystore password:
       Re-enter new password:
       What is your first and last name?
         [Unknown]:  *.example.com
       What is the name of your organizational unit?
         [Unknown]:
       What is the name of your organization?
         [Unknown]:
       What is the name of your City or Locality?
         [Unknown]:
       What is the name of your State or Province?
         [Unknown]:
       What is the two-letter country code for this unit?
         [Unknown]:
       Is CN=*.example.com, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown correct?
         [no]:  yes

   .. Note: Replace `example.com` with the appropriate domain.

4. Distribute the Java Keystore File across the Trino cluster.

5. Enable the HTTPS endpoint.

   .. code-block:: text

       http-server.https.enabled=true
       http-server.https.port=<https port>
       http-server.https.keystore.path=<keystore path>
       http-server.https.keystore.key=<keystore password>

6. Change the discovery uri to HTTPS.

   .. code-block:: text

       discovery.uri=https://<coordinator fqdn>:<https port>

7. Configure the internal communication to require HTTPS.

   .. code-block:: text

       internal-communication.https.required=true

8. Configure the internal communication to use the Java keystore file.

   .. code-block:: text

       internal-communication.https.keystore.path=<keystore path>
       internal-communication.https.keystore.key=<keystore password>


Performance with SSL/TLS enabled
--------------------------------

Enabling encryption impacts performance. The performance degradation can vary
based on the environment, queries, and concurrency.

For queries that do not require transferring too much data between the Trino
nodes e.g. ``SELECT count(*) FROM table``, the performance impact is negligible.

However, for CPU intensive queries which require a considerable amount of data
to be transferred between the nodes (for example, distributed joins, aggregations and
window functions, which require repartitioning), the performance impact can be
considerable. The slowdown may vary from 10% to even 100%+, depending on the network
traffic and the CPU utilization.

Advanced Performance Tuning
---------------------------

In some cases, changing the source of random numbers improves performance
significantly.

By default, TLS encryption uses the ``/dev/urandom`` system device as a source of entropy.
This device has limited throughput, so on environments with high network bandwidth
(e.g. InfiniBand), it may become a bottleneck. In such situations, it is recommended to try
to switch the random number generator algorithm to ``SHA1PRNG``, by setting it via
``http-server.https.secure-random-algorithm`` property in ``config.properties`` on the coordinator
and all of the workers:

.. code-block:: text

    http-server.https.secure-random-algorithm=SHA1PRNG

Be aware that this algorithm takes the initial seed from
the blocking ``/dev/random`` device. For environments that do not have enough entropy to seed
the ``SHAPRNG`` algorithm, the source can be changed to ``/dev/urandom``
by adding the ``java.security.egd`` property to ``jvm.config``:

.. code-block:: text

    -Djava.security.egd=file:/dev/urandom
