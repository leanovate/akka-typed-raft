# A Raft implementation using akka-typed [![Gitter](https://img.shields.io/gitter/room/leanovate-akka-typed-raft/Lobby.svg)](https://gitter.im/leanovate-akka-typed-raft/Lobby)

For good explanation of Raft see: 
* https://raft.github.io/raft.pdf
* https://raft.github.io/

## Development

Requires [sbt](http://www.scala-sbt.org/)

To start an interactive server use the following line and open [localhost:8080](http://localhost:8080).
After each source code change the server and the client will reload. (This feature is provided by [sbt-revolver](https://github.com/spray/sbt-revolver))

```bash
sbt ~reStart
```

To run all tests use

```bash
sbt test
```

### Anatomy the application

The folder `server` contains the Raft algorithm it self written with [Akka-Typed](https://doc.akka.io/docs/akka/current/actors-typed.html#introduction) and a [Akka-HTTP](https://doc.akka.io/docs/akka-http/current/introduction.html#routing-dsl-for-http-servers) web-server to observe the running algorithm (in package `vis`).

The folder `client` contains a web application to visualise the behaviour of Raft.
The application receives updates via [Server-sent events](https://www.sitepoint.com/server-sent-events/) and
manages updates with [Diode](https://diode.suzaku.io/) (conceptually very similar to Redux).
Instead of XML-literals [ScalaTags](http://www.lihaoyi.com/scalatags/) is used, so instead of `<p id="some">Text</p>` you see `p(id := "some", "text")` everwhere.

The folder `shared` just contains code that is useful for the server and the web application.
Right now allow messages that are transferred to the front end lives here.
