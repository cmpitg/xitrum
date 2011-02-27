package xitrum.handler.updown

import java.io.File
import java.io.RandomAccessFile

import org.jboss.netty.channel.{ChannelHandler, SimpleChannelDownstreamHandler, Channels, ChannelHandlerContext, MessageEvent, ChannelFuture, DefaultFileRegion, ChannelFutureListener}
import ChannelHandler.Sharable
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpResponseStatus, HttpVersion}
import HttpResponseStatus._
import HttpVersion._
import HttpHeaders.Names._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.stream.ChunkedFile
import org.jboss.netty.buffer.ChannelBuffers

import xitrum.{Config, Logger}
import xitrum.handler.{Env, SmallFileCache}

object FileSender {
  val CHUNK_SIZE = 8192
}

/**
 * This handler send file to client using various strategies:
 * 1. If the file is big: use zero-copy for HTTP or chunking for HTTPS
 * 2. If the file is small: cache in memory and use normal response
 */
@Sharable
class XSendfile extends SimpleChannelDownstreamHandler with Logger {
  import FileSender._

  override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
    val m = e.getMessage
    if (!m.isInstanceOf[Env]) {
      ctx.sendDownstream(e)
      return
    }

    val env      = m.asInstanceOf[Env]
    val request  = env.request
    val response = env.response
    if (!response.containsHeader("X-Sendfile")) {
      ctx.sendDownstream(e)
      return
    }

    // X-Sendfile is not standard
    // To avoid leaking the information, we remove it
    val abs = response.getHeader("X-Sendfile")
    response.removeHeader("X-Sendfile")

    // Try to serve from cache
    SmallFileCache.get(abs) match {
      case SmallFileCache.Hit(bytes, lastModified, mimeo) =>
        if (request.getHeader(IF_MODIFIED_SINCE) == lastModified) {
          response.setStatus(NOT_MODIFIED)
        } else {
          logger.debug("Serve " + abs + " from cache")
          HttpHeaders.setContentLength(response, bytes.length)
          response.setHeader(LAST_MODIFIED, lastModified)
          if (mimeo.isDefined) response.setHeader(CONTENT_TYPE, mimeo.get)
          response.setContent(ChannelBuffers.wrappedBuffer(bytes))
        }
        ctx.sendDownstream(e)

      case SmallFileCache.FileNotFound =>
        response.setStatus(NOT_FOUND)
        HttpHeaders.setContentLength(response, 0)
        ctx.sendDownstream(e)

      case SmallFileCache.FileTooBig(raf, fileLength, lastModified, mimeo) =>
        if (request.getHeader(IF_MODIFIED_SINCE) == lastModified) {
          response.setStatus(NOT_MODIFIED)
          ctx.sendDownstream(e)
        } else {
          // Write the initial line and the header
          HttpHeaders.setContentLength(response, fileLength)
          response.setHeader(LAST_MODIFIED, lastModified)
          if (mimeo.isDefined) response.setHeader(CONTENT_TYPE, mimeo.get)
          ctx.sendDownstream(e)

          // Write the content
          if (ctx.getPipeline.get(classOf[SslHandler]) != null) {
            // Cannot use zero-copy with HTTPS
            val future = Channels.write(ctx.getChannel, new ChunkedFile(raf, 0, fileLength, CHUNK_SIZE))
            future.addListener(new ChannelFutureListener {
              def operationComplete(f: ChannelFuture) {
                raf.close
              }
            })

            // Keep alive
            if (!HttpHeaders.isKeepAlive(request)) {
              future.addListener(ChannelFutureListener.CLOSE)
            }
          } else {
            // No encryption - use zero-copy
            val region = new DefaultFileRegion(raf.getChannel, 0, fileLength)

            // This will cause ClosedChannelException:
            // Channels.write(ctx, e.getFuture, region)

            val future = Channels.write(ctx.getChannel, region)
            future.addListener(new ChannelFutureListener {
              def operationComplete(f: ChannelFuture) {
                region.releaseExternalResources
                raf.close
              }
            })

            // Keep alive
            if (!HttpHeaders.isKeepAlive(request)) {
              future.addListener(ChannelFutureListener.CLOSE)
            }
          }
        }
    }
  }
}