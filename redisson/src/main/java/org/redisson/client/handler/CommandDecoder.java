/**
 * Copyright (c) 2013-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Copyright 2012 Sam Pullara
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package org.redisson.client.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.redisson.client.RedisAskException;
import org.redisson.client.RedisAuthRequiredException;
import org.redisson.client.RedisException;
import org.redisson.client.RedisLoadingException;
import org.redisson.client.RedisMovedException;
import org.redisson.client.RedisOutOfMemoryException;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.RedisTryAgainException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.QueueCommand;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommand.ValueType;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.decoder.MultiDecoder;
import org.redisson.misc.LogHelper;
import org.redisson.misc.RPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * Redis protocol command decoder
 *
 * @author Nikita Koksharov
 *
 */
public class CommandDecoder extends ReplayingDecoder<State> {

	final Logger log = LoggerFactory.getLogger(getClass());

	private static final char CR = '\r';
	private static final char LF = '\n';
	private static final char ZERO = '0';

	final ExecutorService executor;
	private final boolean decodeInExecutor;

	private final FastThreadLocal<State> state = new FastThreadLocal<State>();

	public CommandDecoder(ExecutorService executor, boolean decodeInExecutor) {
		this.decodeInExecutor = decodeInExecutor;
		this.executor = executor;
	}

	@Override
	protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		QueueCommand data = ctx.channel().attr(CommandsQueue.CURRENT_COMMAND).get();

		if (state.get() == null) {
			state.set(new State());
		}

		try {

			state.get().setDecoderState(null);

			if (data == null) {
				while (in.writerIndex() > in.readerIndex()) {
					int endIndex = skipCommand(in);

					try {
						decode(ctx, in, data);
					} catch (Exception e) {
						in.readerIndex(endIndex);
						throw e;
					}
				}
			} else {
				int endIndex = 0;
				if (!(data instanceof CommandsData)) {
					endIndex = skipCommand(in);
				}

				try {
					decode(ctx, in, data);
				} catch (Exception e) {
					if (!(data instanceof CommandsData)) {
						in.readerIndex(endIndex);
					}
					throw e;
				}
			}

		} finally {

			state.remove();

		}
	}

	private void decode(ChannelHandlerContext ctx, ByteBuf in, QueueCommand data) throws Exception {
		if (log.isTraceEnabled()) {
			log.trace("reply: {}, channel: {}, command: {}", in.toString(0, in.writerIndex(), CharsetUtil.UTF_8),
					ctx.channel(), data);
		}

		if (decodeInExecutor && !(data instanceof CommandsData)) {
			ByteBuf copy = in.copy(in.readerIndex(), in.writerIndex() - in.readerIndex());
			in.skipBytes(in.writerIndex() - in.readerIndex());
			executor.execute(() -> {
				state.set(new State());
				state.get().setDecoderState(null);

				try {
					decodeCommand(ctx.channel(), copy, data);
				} catch (Exception e) {
					log.error("Unable to decode data in separate thread: " + LogHelper.toString(data), e);
				} finally {
					copy.release();
				}
			});
		} else {
			decodeCommand(ctx.channel(), in, data);
		}
	}

	protected void sendNext(Channel channel, QueueCommand data) {
		if (data != null) {
			if (data.isExecuted()) {
				sendNext(channel);
			}
		} else {
			sendNext(channel);
		}
	}

	protected int skipCommand(ByteBuf in) throws Exception {
		in.markReaderIndex();
		skipDecode(in);
		int res = in.readerIndex();
		in.resetReaderIndex();
		return res;
	}

	protected void skipDecode(ByteBuf in) throws IOException {
		int code = in.readByte();
		if (code == '+') {
			skipString(in);
		} else if (code == '-') {
			skipString(in);
		} else if (code == ':') {
			skipString(in);
		} else if (code == '$') {
			skipBytes(in);
		} else if (code == '*') {
			long size = readLong(in);
			for (int i = 0; i < size; i++) {
				skipDecode(in);
			}
		}
	}

	private void skipBytes(ByteBuf is) throws IOException {
		long l = readLong(is);
		if (l > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Java only supports arrays up to " + Integer.MAX_VALUE + " in size");
		}
		int size = (int) l;
		if (size == -1) {
			return;
		}
		is.skipBytes(size + 2);
	}

	private void skipString(ByteBuf in) {
		int len = in.bytesBefore((byte) '\r');
		in.skipBytes(len + 2);
	}

	protected void decodeCommand(Channel channel, ByteBuf in, QueueCommand data) throws Exception {
		if (data instanceof CommandData) {
			CommandData<Object, Object> cmd = (CommandData<Object, Object>) data;
			try {
				decode(in, cmd, null, channel, false, null);
				sendNext(channel, data);
			} catch (Exception e) {
				log.error("Unable to decode data. channel: " + channel + ", reply: " + LogHelper.toString(in)
						+ ", command: " + LogHelper.toString(data), e);
				cmd.tryFailure(e);
				sendNext(channel);
				throw e;
			}
		} else if (data instanceof CommandsData) {
			CommandsData commands = (CommandsData) data;
			try {
				decodeCommandBatch(channel, in, data, commands);
			} catch (Exception e) {
				commands.getPromise().tryFailure(e);
				sendNext(channel);
				throw e;
			}
		} else {
			try {
				while (in.writerIndex() > in.readerIndex()) {
					decode(in, null, null, channel, false, null);
				}
				sendNext(channel);
			} catch (Exception e) {
				log.error("Unable to decode data. channel: " + channel + ", reply: " + LogHelper.toString(in), e);
				sendNext(channel);
				throw e;
			}
		}
	}

	protected void sendNext(Channel channel) {
		channel.pipeline().get(CommandsQueue.class).sendNextCommand(channel);
		state.set(null);
	}

	private void decodeCommandBatch(Channel channel, ByteBuf in, QueueCommand data, CommandsData commandBatch)
			throws Exception {
		int i = state.get().getBatchIndex();

		Throwable error = null;
		while (in.writerIndex() > in.readerIndex()) {
			CommandData<Object, Object> commandData = null;

			checkpoint();
			state.get().setBatchIndex(i);

			int endIndex = skipCommand(in);
			try {

				RedisCommand<?> cmd = commandBatch.getCommands().get(i).getCommand();
				boolean skipConvertor = commandBatch.isQueued();
				List<CommandData<?, ?>> commandsData = null;
				if (!commandBatch.isAtomic() || RedisCommands.EXEC.getName().equals(cmd.getName())
						|| RedisCommands.WAIT.getName().equals(cmd.getName())) {
					commandData = (CommandData<Object, Object>) commandBatch.getCommands().get(i);
					if (RedisCommands.EXEC.getName().equals(cmd.getName())) {
						skipConvertor = false;
						if (commandBatch.getAttachedCommands() != null) {
							commandsData = commandBatch.getAttachedCommands();
						} else {
							commandsData = commandBatch.getCommands();
						}
					}
				}

				decode(in, commandData, null, channel, skipConvertor, commandsData);

				if (commandData != null && RedisCommands.EXEC.getName().equals(commandData.getCommand().getName())
						&& commandData.getPromise().isSuccess()) {
					List<Object> objects = (List<Object>) commandData.getPromise().getNow();
					Iterator<Object> iter = objects.iterator();
					boolean multiFound = false;
					for (CommandData<?, ?> command : commandBatch.getCommands()) {
						if (multiFound) {
							if (!iter.hasNext()) {
								break;
							}
							Object res = iter.next();

							completeResponse((CommandData<Object, Object>) command, res, channel);
						}

						if (RedisCommands.MULTI.getName().equals(command.getCommand().getName())) {
							multiFound = true;
						}
					}
				}
			} catch (Exception e) {
				in.readerIndex(endIndex);
				if (commandData != null) {
					commandData.tryFailure(e);
				}
			}
			i++;
			if (commandData != null && !commandData.isSuccess()) {
				error = commandData.cause();
			}
		}

		if (commandBatch.isSkipResult() || i == commandBatch.getCommands().size()) {
			RPromise<Void> promise = commandBatch.getPromise();
			if (error != null) {
				if (!promise.tryFailure(error) && promise.cause() instanceof RedisTimeoutException) {
					log.warn("response has been skipped due to timeout! channel: {}, command: {}", channel,
							LogHelper.toString(data));
				}
			} else {
				if (!promise.trySuccess(null) && promise.cause() instanceof RedisTimeoutException) {
					log.warn("response has been skipped due to timeout! channel: {}, command: {}", channel,
							LogHelper.toString(data));
				}
			}

			sendNext(channel);
		} else {
			checkpoint();
			state.get().setBatchIndex(i);
		}
	}

	protected void decode(ByteBuf in, CommandData<Object, Object> data, List<Object> parts, Channel channel,
			boolean skipConvertor, List<CommandData<?, ?>> commandsData) throws IOException {
		int code = in.readByte();
		if (code == '+') {
			String result = readString(in);

			handleResult(data, parts, result, skipConvertor, channel);
		} else if (code == '-') {
			String error = readString(in);

			if (error.startsWith("MOVED")) {
				String[] errorParts = error.split(" ");
				int slot = Integer.valueOf(errorParts[1]);
				String addr = errorParts[2];
				data.tryFailure(new RedisMovedException(slot, addr));
			} else if (error.startsWith("ASK")) {
				String[] errorParts = error.split(" ");
				int slot = Integer.valueOf(errorParts[1]);
				String addr = errorParts[2];
				data.tryFailure(new RedisAskException(slot, addr));
			} else if (error.startsWith("TRYAGAIN")) {
				data.tryFailure(new RedisTryAgainException(error + ". channel: " + channel + " data: " + data));
			} else if (error.startsWith("LOADING")) {
				data.tryFailure(new RedisLoadingException(error + ". channel: " + channel + " data: " + data));
			} else if (error.startsWith("OOM")) {
				data.tryFailure(new RedisOutOfMemoryException(
						error.split("OOM ")[1] + ". channel: " + channel + " data: " + data));
			} else if (error.contains("-OOM ")) {
				data.tryFailure(new RedisOutOfMemoryException(
						error.split("-OOM ")[1] + ". channel: " + channel + " data: " + data));
			} else if (error.startsWith("NOAUTH")) {
				data.tryFailure(new RedisAuthRequiredException(error + ". channel: " + channel + " data: " + data));
			} else {
				if (data != null) {
					data.tryFailure(new RedisException(
							error + ". channel: " + channel + " command: " + LogHelper.toString(data)));
				} else {
					log.error("Error message from Redis: {} channel: {}", error, channel);
				}
			}
		} else if (code == ':') {
			Long result = readLong(in);
			handleResult(data, parts, result, false, channel);
		} else if (code == '$') {
			ByteBuf buf = readBytes(in);
			Object result = null;
			if (buf != null) {
				Decoder<Object> decoder = selectDecoder(data, parts);
				result = decoder.decode(buf, state.get());
			}
			handleResult(data, parts, result, false, channel);
		} else if (code == '*') {
			long size = readLong(in);
			List<Object> respParts = new ArrayList<Object>(Math.max((int) size, 0));

			state.get().incLevel();

			decodeList(in, data, parts, channel, size, respParts, skipConvertor, commandsData);

			state.get().decLevel();

		} else {
			String dataStr = in.toString(0, in.writerIndex(), CharsetUtil.UTF_8);
			throw new IllegalStateException("Can't decode replay: " + dataStr);
		}
	}

	private String readString(ByteBuf in) {
		int len = in.bytesBefore((byte) '\r');
		String result = in.toString(in.readerIndex(), len, CharsetUtil.UTF_8);
		in.skipBytes(len + 2);
		return result;
	}

	@SuppressWarnings("unchecked")
	private void decodeList(ByteBuf in, CommandData<Object, Object> data, List<Object> parts, Channel channel,
			long size, List<Object> respParts, boolean skipConvertor, List<CommandData<?, ?>> commandsData)
			throws IOException {
		if (parts == null && commandsData != null) {
			for (int i = respParts.size(); i < size; i++) {
				int suffix = 0;
				if (RedisCommands.MULTI.getName().equals(commandsData.get(0).getCommand().getName())) {
					suffix = 1;
				}
				CommandData<Object, Object> commandData = (CommandData<Object, Object>) commandsData.get(i + suffix);
				decode(in, commandData, respParts, channel, skipConvertor, commandsData);
				if (commandData.getPromise().isDone() && !commandData.getPromise().isSuccess()) {
					data.tryFailure(commandData.cause());
				}
			}
		} else {
			for (int i = respParts.size(); i < size; i++) {
				decode(in, data, respParts, channel, skipConvertor, null);
			}
		}

		MultiDecoder<Object> decoder = messageDecoder(data, respParts);
		if (decoder == null) {
			return;
		}

		Object result = decoder.decode(respParts, state.get());
		decodeResult(data, parts, channel, result);
	}

	protected void decodeResult(CommandData<Object, Object> data, List<Object> parts, Channel channel, Object result)
			throws IOException {
		if (data != null) {
			handleResult(data, parts, result, true, channel);
		}
	}

	private void handleResult(CommandData<Object, Object> data, List<Object> parts, Object result,
			boolean skipConvertor, Channel channel) {
		if (data != null && !skipConvertor) {
			result = data.getCommand().getConvertor().convert(result);
		}
		if (parts != null) {
			parts.add(result);
		} else {
			completeResponse(data, result, channel);
		}
	}

	protected void completeResponse(CommandData<Object, Object> data, Object result, Channel channel) {
		if (data != null && !data.getPromise().trySuccess(result) && data.cause() instanceof RedisTimeoutException) {
			log.warn("response has been skipped due to timeout! channel: {}, command: {}", channel,
					LogHelper.toString(data));
		}
	}

	protected MultiDecoder<Object> messageDecoder(CommandData<Object, Object> data, List<Object> parts) {
		if (data == null) {
			if (parts.isEmpty()) {
				return null;
			}
		}
		return data.getCommand().getReplayMultiDecoder();
	}

	protected Decoder<Object> selectDecoder(CommandData<Object, Object> data, List<Object> parts) {
		if (data == null) {
			return StringCodec.INSTANCE.getValueDecoder();
		}

		if (parts != null) {
			MultiDecoder<Object> multiDecoder = data.getCommand().getReplayMultiDecoder();
			if (multiDecoder != null) {
				Decoder<Object> mDecoder = multiDecoder.getDecoder(parts.size(), state.get());
				if (mDecoder != null) {
					return mDecoder;
				}
			}
		}

		Codec codec = data.getCodec();
		Decoder<Object> decoder = data.getCommand().getReplayDecoder();
		if (decoder == null) {
			if (codec == null) {
				return StringCodec.INSTANCE.getValueDecoder();
			}
			if (data.getCommand().getOutParamType() == ValueType.MAP) {
				if (parts != null && parts.size() % 2 != 0) {
					return codec.getMapValueDecoder();
				} else {
					return codec.getMapKeyDecoder();
				}
			} else if (data.getCommand().getOutParamType() == ValueType.MAP_KEY) {
				return codec.getMapKeyDecoder();
			} else if (data.getCommand().getOutParamType() == ValueType.MAP_VALUE) {
				return codec.getMapValueDecoder();
			} else {
				return codec.getValueDecoder();
			}
		}
		return decoder;
	}

	private ByteBuf readBytes(ByteBuf is) throws IOException {
		long l = readLong(is);
		if (l > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Java only supports arrays up to " + Integer.MAX_VALUE + " in size");
		}
		int size = (int) l;
		if (size == -1) {
			return null;
		}
		ByteBuf buffer = is.readSlice(size);
		int cr = is.readByte();
		int lf = is.readByte();
		if (cr != CR || lf != LF) {
			throw new IOException("Improper line ending: " + cr + ", " + lf);
		}
		return buffer;
	}

	private long readLong(ByteBuf is) throws IOException {
		long size = 0;
		int sign = 1;
		int read = is.readByte();
		if (read == '-') {
			read = is.readByte();
			sign = -1;
		}
		do {
			if (read == CR) {
				if (is.readByte() == LF) {
					break;
				}
			}
			int value = read - ZERO;
			if (value >= 0 && value < 10) {
				size *= 10;
				size += value;
			} else {
				throw new IOException("Invalid character in integer");
			}
			read = is.readByte();
		} while (true);
		return size * sign;
	}

}
