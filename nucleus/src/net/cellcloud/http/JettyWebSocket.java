/*
-----------------------------------------------------------------------------
This source file is part of Cell Cloud.

Copyright (c) 2009-2014 Cell Cloud Team (www.cellcloud.net)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
-----------------------------------------------------------------------------
*/

package net.cellcloud.http;

import java.net.InetSocketAddress;
import java.util.Vector;

import net.cellcloud.common.Logger;
import net.cellcloud.common.Message;
import net.cellcloud.common.MessageErrorCode;
import net.cellcloud.common.MessageHandler;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * 
 * @author Jiangwei Xu
 */
@WebSocket(maxTextMessageSize = 64 * 1024, maxBinaryMessageSize = 64 * 1024)
public final class JettyWebSocket implements WebSocketManager {

	private MessageHandler handler;
	private Vector<Session> sessions;
	private Vector<WebSocketSession> wsSessions;

	public JettyWebSocket(MessageHandler handler) {
		this.handler = handler;
		this.sessions = new Vector<Session>();
		this.wsSessions = new Vector<WebSocketSession>();
	}

	@OnWebSocketMessage
	public void onWebSocketBinary(Session session, byte[] buf, int offset, int length) {
		Logger.d(this.getClass(), "onWebSocketBinary");

		if (!session.isOpen()) {
			Logger.w(this.getClass(), "Session is closed");
			return;
		}

		/*
		RemoteEndpoint remote = this.session.getRemote();
		remote.sendBytes(ByteBuffer.wrap(buf, offset, length), null);
		if (remote.getBatchMode() == BatchMode.ON) {
			try {
				remote.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		*/
	}

	@OnWebSocketMessage
	public void onWebSocketText(Session session, String text) {
		//Logger.d(this.getClass(), "onWebSocketText");

		if (!session.isOpen()) {
			Logger.w(this.getClass(), "Session is closed");
			return;
		}

		WebSocketSession wsSession = null;
		synchronized (this.sessions) {
			int index = this.sessions.indexOf(session);
			wsSession = this.wsSessions.get(index);
		}

		if (null != this.handler) {
			Message message = new Message(text);
			this.handler.messageReceived(wsSession, message);
		}

		/*
		RemoteEndpoint remote = this.session.getRemote();
		remote.sendString(text, null);
		if (remote.getBatchMode() == BatchMode.ON) {
			try {
				remote.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		*/
	}

	@OnWebSocketConnect
	public void onWebSocketConnect(Session session) {
		Logger.d(this.getClass(), "onWebSocketConnect");

		synchronized (this.sessions) {
			if (this.sessions.contains(session)) {
				return;
			}
		}

		InetSocketAddress address = new InetSocketAddress(session.getRemoteAddress().getAddress().getHostAddress()
				, session.getRemoteAddress().getPort());
		WebSocketSession wsSession = new WebSocketSession(address, session);

		// 添加 session
		synchronized (this.sessions) {
			this.sessions.add(session);
			this.wsSessions.add(wsSession);
		}

		if (null != this.handler) {
			this.handler.sessionCreated(wsSession);
			this.handler.sessionOpened(wsSession);
		}
	}

	@OnWebSocketClose
	public void onWebSocketClose(Session session, int code, String reason) {
		Logger.d(this.getClass(), "onWebSocketClose");

		int index = -1;
		synchronized (this.sessions) {
			index = this.sessions.indexOf(session);
			if (index < 0) {
				return;
			}
		}

		WebSocketSession wsSession = null;
		synchronized (this.sessions) {
			wsSession = this.wsSessions.get(index);
		}

		if (null != this.handler) {
			this.handler.sessionClosed(wsSession);
			this.handler.sessionDestroyed(wsSession);
		}

		synchronized (this.sessions) {
			this.sessions.remove(index);
			this.wsSessions.remove(index);
		}
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable cause) {
		Logger.w(this.getClass(), "onWebSocketError: " + cause.getMessage());

		WebSocketSession wsSession = null;

		int index = this.sessions.indexOf(session);
		if (index >= 0) {
			wsSession = this.wsSessions.get(index);
		}

		if (null != this.handler) {
			this.handler.errorOccurred(MessageErrorCode.SOCKET_FAILED, wsSession);
		}
	}

	@Override
	public void write(WebSocketSession session, Message message) {
		session.write(message);
	}

	@Override
	public void close(WebSocketSession session) {
		int index = this.wsSessions.indexOf(session);
		if (index >= 0) {
			Session rawSession = this.sessions.get(index);
			rawSession.close(1000, "Server close this session");
		}
	}
}
