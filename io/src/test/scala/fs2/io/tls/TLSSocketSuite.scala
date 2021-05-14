/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package tls

import scala.util.control.NonFatal
import scala.concurrent.duration._

import java.net.{InetAddress, InetSocketAddress}
import javax.net.ssl.{SNIHostName, SSLContext}

import cats.effect.{Blocker, IO}
import cats.syntax.all._

import fs2.io.tcp.SocketGroup

import java.security.Security

class TLSSocketSuite extends TLSSuite {
  group("TLSSocket") {
    group("google") {
      List("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").foreach { protocol =>
        val supportedByPlatform: Boolean =
          Either
            .catchNonFatal(SSLContext.getInstance(protocol))
            .isRight

        val enabled: Boolean =
          Either
            .catchNonFatal {
              val disabledAlgorithms = Security.getProperty("jdk.tls.disabledAlgorithms")
              !disabledAlgorithms.contains(protocol)
            }
            .getOrElse(false)

        if (!supportedByPlatform)
          test(s"$protocol - not supported by this platform".ignore) {}
        else if (!enabled)
          test(s"$protocol - disabled on this platform".ignore) {}
        else
          test(protocol) {
            Blocker[IO].use { blocker =>
              SocketGroup[IO](blocker).use { socketGroup =>
                socketGroup.client[IO](new InetSocketAddress("www.google.com", 443)).use { socket =>
                  TLSContext
                    .system[IO](blocker)
                    .flatMap { ctx =>
                      ctx
                        .client(
                          socket,
                          TLSParameters(
                            protocols = Some(List(protocol)),
                            serverNames = Some(List(new SNIHostName("www.google.com")))
                          ),
                          logger = None // Some(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
                        )
                        .use { tlsSocket =>
                          (Stream("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
                            .covary[IO]
                            .through(text.utf8Encode)
                            .through(tlsSocket.writes())
                            .drain ++
                            Stream.eval_(tlsSocket.endOfOutput) ++
                            tlsSocket
                              .reads(8192)
                              .through(text.utf8Decode)
                              .through(text.lines)).head.compile.string
                        }
                        .map(it => assert(it == "HTTP/1.1 200 OK"))
                    }
                }
              }
            }
          }
      }
    }

    test("echo") {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          testTlsContext(blocker).flatMap { tlsContext =>
            socketGroup
              .serverResource[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
              .use { case (serverAddress, clients) =>
                val server = clients.map { client =>
                  Stream.resource(client).flatMap { clientSocket =>
                    Stream.resource(tlsContext.server(clientSocket)).flatMap { clientSocketTls =>
                      clientSocketTls.reads(8192).chunks.flatMap { c =>
                        Stream.eval(clientSocketTls.write(c))
                      }
                    }
                  }
                }.parJoinUnbounded

                val msg = Chunk.bytes(("Hello, world! " * 20000).getBytes)
                val client =
                  Stream.resource(socketGroup.client[IO](serverAddress)).flatMap { clientSocket =>
                    Stream
                      .resource(
                        tlsContext.client(
                          clientSocket
                          // logger = Some((m: String) =>
                          //   IO.delay(println(s"${Console.MAGENTA}[TLS] $m${Console.RESET}"))
                          // )
                        )
                      )
                      .flatMap { clientSocketTls =>
                        Stream.eval_(clientSocketTls.write(msg)) ++
                          clientSocketTls.reads(8192).take(msg.size)
                      }
                  }

                client.concurrently(server).compile.to(Chunk).map(it => assert(it == msg))
              }
          }
        }
      }
    }

    test("client reads before writing") {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { rawSocket =>
            TLSContext.system[IO](blocker).flatMap { tlsContext =>
              tlsContext
                .client[IO](
                  rawSocket,
                  TLSParameters(
                    serverNames = Some(List(new SNIHostName("www.google.com")))
                  )
                  // logger = Some((m: String) =>
                  //  IO.delay(println(s"${Console.MAGENTA}[TLS] $m${Console.RESET}"))
                  //)
                )
                .use { socket =>
                  val send = Stream("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
                    .through(text.utf8Encode)
                    .through(socket.writes())
                  val receive = socket
                    .reads(8192)
                    .through(text.utf8Decode)
                    .through(text.lines)
                    .head
                  receive
                    .concurrently(send.delayBy(100.millis))
                    .compile
                    .string
                    .map(it => assertEquals(it, "HTTP/1.1 200 OK"))
                }
            }
          }
        }
      }
    }
  }
}
