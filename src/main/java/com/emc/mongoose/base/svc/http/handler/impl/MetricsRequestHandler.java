package com.emc.mongoose.base.svc.http.handler.impl;

import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.svc.http.handler.UriPrefixMatchingRequestHandlerBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.logging.log4j.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashSet;

import static com.emc.mongoose.base.Constants.MIB;
import static com.emc.mongoose.base.svc.http.handler.ResponseUtil.respondContent;
import static com.emc.mongoose.base.svc.http.handler.ResponseUtil.respondEmptyContent;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

@ChannelHandler.Sharable
public final class MetricsRequestHandler
extends UriPrefixMatchingRequestHandlerBase {

	private final CollectorRegistry registry;

	public MetricsRequestHandler() {
		this(CollectorRegistry.defaultRegistry);
	}

	public MetricsRequestHandler(final CollectorRegistry registry) {
		this.registry = registry;
	}

	@Override
	protected final String uriPrefix() {
		return "/metrics";
	}

	@Override
	protected final void handle(final ChannelHandlerContext ctx, final FullHttpRequest req) {
		// parse request
		final var reqQueryDecoder = new QueryStringDecoder(req.uri());
		final var includedParam = reqQueryDecoder.parameters().getOrDefault("name[]", Collections.emptyList());
		final var filteredMetrics = registry.filteredMetricFamilySamples(new HashSet<>(includedParam));
		var respContent = (ByteBuf) null;
		try(
			final var out = new ByteArrayOutputStream(MIB);
			final var writer = new OutputStreamWriter(out);
		) {
			TextFormat.write004(writer, filteredMetrics);
			writer.flush();
			respContent = Unpooled.wrappedBuffer(out.toByteArray());
		} catch(final IOException e) {
			LogUtil.exception(Level.ERROR, e, "Unexpected failure");
		}
		// make response
		if(null == respContent) {
			respondEmptyContent(ctx, INTERNAL_SERVER_ERROR);
		} else {
			respondContent(ctx, OK, respContent, TextFormat.CONTENT_TYPE_004);
		}
	}
}
