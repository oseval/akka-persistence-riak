Riak Plugin for Akka Persistence
================================

[Akka Persistence](http://doc.akka.io/docs/akka/2.3.6/scala/persistence.html) journal store backed by [Riak](http://basho.com/riak/).

Dependency
----------

This version of `akka-persistence-riak` depends on Riak 2.0 and Akka 2.3.9.


Journal plugin
--------------

### Activation 

To activate the journal plugin, add the following lines to `application.conf`:

    akka.persistence.journal.plugin = "akka-persistence-riak-async.journal"
    akka-persistence-riak-async.journal.class = "docs.persistence.MyJournal"

You also need to configure the riak hosts:

    akka-persistence-riak-async.nodes = ["127.0.0.1"]
