/*
-----------------------------------------------------------------------------
This source file is part of Cell Cloud.

Copyright (c) 2009-2012 Cell Cloud Team (cellcloudproject@gmail.com)

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

package net.cellcloud.extras;

import net.cellcloud.common.Message;
import net.cellcloud.common.MessageHandler;
import net.cellcloud.common.NonblockingConnector;
import net.cellcloud.common.Packet;
import net.cellcloud.common.Session;
import net.cellcloud.exception.StorageException;
import net.cellcloud.storage.ResultSet;
import net.cellcloud.storage.SingleFileStorage;
import net.cellcloud.storage.StorageEnumerator;
import net.cellcloud.util.Util;

/** 文件传输任务类。
 * 
 * @author Jiangwei Xu
 */
public final class FileExpressTask extends MessageHandler implements Runnable {

	/// 未知状态
	protected final static int EXPRESS_STATE_UNKNOWN = 0;
	/// 通信连接丢失状态
	protected final static int EXPRESS_STATE_LOST = 1;
	/// 属性确认状态
	protected final static int EXPRESS_STATE_ATTR = 2;
	/// 准备状态
	protected final static int EXPRESS_STATE_PREPARE = 3;
	/// 开始状态
	protected final static int EXPRESS_STATE_BEGIN = 4;
	/// 数据传输状态
	protected final static int EXPRESS_STATE_DATA = 5;
	/// 结束状态
	protected final static int EXPRESS_STATE_END = 6;
	/// 未通过授权检查
	protected final static int EXPRESS_STATE_UNAUTH = 9;
	/// 任务完成状态
	protected final static int EXPRESS_STATE_EXIT = 11;

	private FileExpressContext context;
	private ExpressTaskListener listener;
	private int state;

	/// 最大重试次数
	private int maxRetryCount;
	private int retryCount;

	private byte[] dataCache;

	private SingleFileStorage fileStorage;
	private long progress;

	private byte[] monitor = new byte[0];

	public FileExpressTask(FileExpressContext context) {
		this.context = context;
		this.listener = null;
		this.state = EXPRESS_STATE_UNKNOWN;
		this.maxRetryCount = 3;
		this.retryCount = 0;
		this.dataCache = null;
		this.fileStorage = null;
		this.progress = 0;
	}

	/** 设置监听器。
	 */
	public void setListener(ExpressTaskListener listener) {
		this.listener = listener;
	}

	@Override
	public void run() {
		switch (this.context.getOperate()) {
		case FileExpressContext.OP_DOWNLOAD:
			download();
			break;
		case FileExpressContext.OP_UPLOAD:
			upload();
			break;
		default:
			break;
		}
	}

	public void abort() {
		
	}

	private void download() {
		if (null == this.fileStorage) {
			this.fileStorage = (SingleFileStorage) StorageEnumerator.getInstance().createStorage(
					SingleFileStorage.FACTORY_TYPE_NAME, "FileDownload" + this.toString());
		}

		// 打开文件
		if (!this.fileStorage.open(this.context.getFullPath())) {
			if (null != this.listener) {
				this.context.errorCode = FileExpressContext.EC_OTHERS;
				this.listener.expressError(this.context);
			}
			return;
		}

		ResultSet resultSet = this.fileStorage.store(this.fileStorage.generateReadStatement());
		// 移动游标
		resultSet.next();

		// 检查文件是否存在并确定起始下载位置
		if (resultSet.getBool(SingleFileStorage.FIELD_EXIST)) {
			this.progress = resultSet.getLong(SingleFileStorage.FIELD_SIZE);
		}
		else {
			this.progress = 0;
		}

		// 写文件用结果集
		resultSet = this.fileStorage.store(this.fileStorage.generateWriteStatement());
		resultSet.next();

		NonblockingConnector connector = new NonblockingConnector();
		byte[] headMark = {0x10, 0x04, 0x11, 0x24};
		byte[] tailMark = {0x11, 0x24, 0x10, 0x04};
		connector.defineDataMark(headMark, tailMark);
		connector.setConnectTimeout(5000);
		connector.setHandler(this);

		this.state = EXPRESS_STATE_LOST;
		this.retryCount = -1;

		while (this.state != EXPRESS_STATE_EXIT) {
			switch (this.state) {
			case EXPRESS_STATE_LOST: {
				if (false == connector.isConnected()) {
					// 连接
					boolean ret = connector.connect(this.context.getAddress());
					if (false == ret) {
						if (this.retryCount < this.maxRetryCount) {
							// 5 秒后重试
							try {
								Thread.sleep(5000);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							// 重试计数
							++this.retryCount;
							break;
						}
						else {
							// 达到最大重试次数
							this.context.errorCode = FileExpressContext.EC_NETWORK_FAULT;
							if (null != this.listener) {
								this.listener.expressError(this.context);
							}
							return;
						}
					}
					else {
						++this.retryCount;

						if (this.retryCount >= this.maxRetryCount) {
							// 达到最大重试次数
							this.context.errorCode = FileExpressContext.EC_NETWORK_FAULT;
							if (null != this.listener) {
								this.listener.expressError(this.context);
							}
							connector.disconnect();
							return;
						}

						synchronized (this.monitor) {
							try {
								this.monitor.wait();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}

						if (FileExpressContext.EC_NETWORK_FAULT == this.context.errorCode) {
							try {
								Thread.sleep(5000);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}

						// 退出 switch-case，重新判断状态
						break;
					}
				}

				synchronized (this.monitor) {
					this.context.errorCode = FileExpressContext.EC_SUCCESS;

					// 发送验证码进行验证。
					postAuthCode(connector.getSession());

					try {
						this.monitor.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				break;
			}
			case EXPRESS_STATE_ATTR:
			{
				synchronized (this.monitor) {
					// 请求文件报告
					queryFile(connector.getSession());

					try {
						this.monitor.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				break;
			}
			case EXPRESS_STATE_PREPARE:
			{
				synchronized (this.monitor) {
					FileAttribute attr = this.context.getAttribute();
					if (!attr.exist() || attr.size() <= this.progress) {
						// 服务器端文件不存在，不进行下载
						// 服务器端文件小于或者等于本地文件大小，不进行下载
						this.state = EXPRESS_STATE_EXIT;

						this.context.errorCode = attr.exist() ? FileExpressContext.EC_REJECT_SIZE : FileExpressContext.EC_FILE_NOEXIST;
						if (null != this.listener) {
							this.listener.expressError(this.context);
						}
					}
					else {
						// 开始下载
						beginDownload(connector.getSession());

						try {
							this.monitor.wait();
						} catch (InterruptedException e) {
							this.state = EXPRESS_STATE_EXIT;
						}
					}
				}
				break;
			}
			case EXPRESS_STATE_DATA:
			{
				synchronized (this.monitor) {
					// 写入数据
					processDownloadData(connector.getSession(), resultSet);

					try {
						this.monitor.wait();
					} catch (InterruptedException e) {
						this.state = EXPRESS_STATE_EXIT;
					}
				}

				break;
			}
			case EXPRESS_STATE_BEGIN:
			{
				synchronized (this.monitor) {
					offerDownload(connector.getSession(), resultSet);

					try {
						this.monitor.wait();
					} catch (InterruptedException e) {
						this.state = EXPRESS_STATE_EXIT;
					}
				}
				break;
			}
			case EXPRESS_STATE_END:
			{
				synchronized (this.monitor) {
					endDownload(connector.getSession());

					// 结束任务循环
					this.state = EXPRESS_STATE_EXIT;
				}
				break;
			}
			default:
				break;
			}
		}

		// 关闭存储器
		this.fileStorage.close();

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// 关闭连接
		connector.disconnect();

		this.fileStorage = null;
	}

	private void upload() {
		
	}

	private void postAuthCode(Session session) {
		// 包格式：授权码
		Packet packet = new Packet(FileExpressDefinition.PT_AUTH, 1, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());

		byte[] data = Packet.pack(packet);
		Message message = new Message(data);
		session.write(message);
	}

	private void queryFile(Session session) {
		// 包格式：授权码|文件名
		Packet packet = new Packet(FileExpressDefinition.PT_ATTR, 2, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());
		packet.appendSubsegment(Util.string2Bytes(this.context.getFileName()));

		byte[] data = Packet.pack(packet);
		Message message = new Message(data);
		session.write(message);
	}

	private void beginDownload(Session session) {
		// 设置总字节数
		this.context.bytesTotal = this.context.getAttribute().size() - this.progress;
		if (null != this.listener) {
			this.listener.expressStarted(this.context);
		}

		// 包格式：授权码|文件名|文件长度|操作
		Packet packet = new Packet(FileExpressDefinition.PT_BEGIN, 3, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());
		packet.appendSubsegment(Util.string2Bytes(this.context.getFileName()));
		packet.appendSubsegment(Long.toString(this.context.getAttribute().size()).getBytes());
		packet.appendSubsegment(Integer.toString(FileExpressContext.OP_DOWNLOAD).getBytes());

		byte[] data = Packet.pack(packet);
		if (null != data) {
			Message message = new Message(data);
			session.write(message);
		}
	}

	private void offerDownload(Session session, ResultSet resultSet) {
		// 设置文件长度
		resultSet.updateLong(SingleFileStorage.FIELD_SIZE, this.context.getAttribute().size());

		// 包格式：授权码|文件名|文件操作起始位置
		Packet packet = new Packet(FileExpressDefinition.PT_OFFER, 4, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());
		packet.appendSubsegment(Util.string2Bytes(this.context.getFileName()));
		packet.appendSubsegment(Long.toString(this.progress).getBytes());

		byte[] data = Packet.pack(packet);
		if (null != data) {
			Message message = new Message(data);
			session.write(message);
		}
	}

	private void endDownload(Session session) {
		if (null != this.fileStorage) {
			this.fileStorage.close();
		}

		// 包结构：授权码|文件名|文件长度|操作
		Packet packet = new Packet(FileExpressDefinition.PT_END, 6, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());
		packet.appendSubsegment(Util.string2Bytes(this.context.getFileName()));
		packet.appendSubsegment(Long.toString(this.context.getAttribute().size()).getBytes());
		packet.appendSubsegment(Integer.toString(this.context.getOperate()).getBytes());

		byte[] data = Packet.pack(packet);
		if (null != data) {
			Message message = new Message(data);
			session.write(message);
		}

		// 回调监听器
		if (null != this.listener) {
			this.listener.expressCompleted(this.context);
		}
	}

	private void processDownloadData(Session session, ResultSet resultSet) {
		// 写数据
		try {
			resultSet.updateRaw(SingleFileStorage.FIELD_DATA, this.dataCache,
					this.progress, (long)this.dataCache.length);
		} catch (StorageException e) {
			this.state = EXPRESS_STATE_EXIT;
			return;
		}

		// 更新进度
		this.progress += this.dataCache.length;

		// 设置已完成的数据长度
		this.context.bytesLoaded = this.progress;
		if (null != this.listener) {
			this.listener.expressProgress(this.context);
		}

		// 发送数据回执
		// 包格式：授权码|文件名|新数据进度
		Packet packet = new Packet(FileExpressDefinition.PT_DATA_RECEIPT, 5, 1, 0);
		packet.appendSubsegment(this.context.getAuthCode().getCode().getBytes());
		packet.appendSubsegment(Util.string2Bytes(this.context.getFileName()));
		packet.appendSubsegment(Long.toString(this.progress).getBytes());

		byte[] data = Packet.pack(packet);
		if (null != data) {
			Message message = new Message(data);
			session.write(message);
		}
	}

	private void interpret(Session session, Packet packet) {
		byte[] tag = packet.getTag();

		synchronized (this.monitor) {
			if (tag[0] == FileExpressDefinition.PT_DATA_RECEIPT[0]
				&& tag[1] == FileExpressDefinition.PT_DATA_RECEIPT[1]
				&& tag[2] == FileExpressDefinition.PT_DATA_RECEIPT[2]
				&& tag[3] == FileExpressDefinition.PT_DATA_RECEIPT[3]) {
				
			}
			else if (tag[0] == FileExpressDefinition.PT_DATA[0]
				&& tag[1] == FileExpressDefinition.PT_DATA[1]
				&& tag[2] == FileExpressDefinition.PT_DATA[2]
				&& tag[3] == FileExpressDefinition.PT_DATA[3]) {
				// 包格式：授权码|文件名|数据起始位|数据结束位|数据
				if (packet.getSubsegmentNumber() < 5) {
					// 包格式错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					this.state = EXPRESS_STATE_EXIT;
					this.monitor.notify();
					return;
				}

				// 检查数据长度
				String szStart = new String(packet.getSubsegment(2));
				long start = Long.parseLong(szStart);
				String szEnd = new String(packet.getSubsegment(3));
				long end = Long.parseLong(szEnd);
				this.dataCache = packet.getSubsegment(4);
				if (end - start == this.dataCache.length) {
					this.progress = start;
					this.state = EXPRESS_STATE_DATA;
				}
				else {
					// 包格式错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					this.state = EXPRESS_STATE_EXIT;
				}

				this.monitor.notify();
			}
			else if (tag[0] == FileExpressDefinition.PT_END[0]
				&& tag[1] == FileExpressDefinition.PT_END[1]
				&& tag[2] == FileExpressDefinition.PT_END[2]
				&& tag[3] == FileExpressDefinition.PT_END[3]) {
				// 包格式：文件名|文件长度
				if (packet.getSubsegmentNumber() < 2) {
					// 包格式错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					this.state = EXPRESS_STATE_EXIT;
					this.monitor.notify();
					return;
				}

				this.state = EXPRESS_STATE_END;
				this.monitor.notify();
			}
			else if (tag[0] == FileExpressDefinition.PT_BEGIN[0]
				&& tag[1] == FileExpressDefinition.PT_BEGIN[1]
				&& tag[2] == FileExpressDefinition.PT_BEGIN[2]
				&& tag[3] == FileExpressDefinition.PT_BEGIN[3]) {
				// 包格式：文件名|文件长度
				if (packet.getSubsegmentNumber() < 2) {
					// 包格式错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					this.state = EXPRESS_STATE_EXIT;
					this.monitor.notify();
					return;
				}

				// 进入开始状态
				this.state = EXPRESS_STATE_BEGIN;
				this.monitor.notify();
			}
			else if (tag[0] == FileExpressDefinition.PT_ATTR[0]
				&& tag[1] == FileExpressDefinition.PT_ATTR[1]
				&& tag[2] == FileExpressDefinition.PT_ATTR[2]
				&& tag[3] == FileExpressDefinition.PT_ATTR[3]) {
				// 包格式：文件名|属性序列
				if (packet.getSubsegmentNumber() < 2) {
					// 包格式错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					// 变更状态
					this.state = EXPRESS_STATE_EXIT;
					this.monitor.notify();
					return;
				}

				String fileName = Util.bytes2String(packet.getSubsegment(0));
				if (this.context.getFileName().equals(fileName)) {
					this.context.getAttribute().deserialize(packet.getSubsegment(1));
					// 变更状态
					this.state = EXPRESS_STATE_PREPARE;
				}
				else {
					// 包错误
					this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
					if (null != this.listener) {
						this.listener.expressError(this.context);
					}

					// 变更状态
					this.state = EXPRESS_STATE_EXIT;
				}

				this.monitor.notify();
			}
			else if (tag[0] == FileExpressDefinition.PT_AUTH[0]
				&& tag[1] == FileExpressDefinition.PT_AUTH[1]
				&& tag[2] == FileExpressDefinition.PT_AUTH[2]
				&& tag[3] == FileExpressDefinition.PT_AUTH[3]) {
				// 权限判定
				byte[] auth = packet.getSubsegment(0);
				this.context.getAuthCode().changeAuth(auth);

				boolean authorized = false;
				switch (this.context.getOperate()) {
				case FileExpressContext.OP_DOWNLOAD:
					if (this.context.getAuthCode().getAuth() == ExpressAuthCode.AUTH_WRITE
						|| this.context.getAuthCode().getAuth() == ExpressAuthCode.AUTH_READ) {
						authorized = true;
					}
					break;
				case FileExpressContext.OP_UPLOAD:
					if (this.context.getAuthCode().getAuth() == ExpressAuthCode.AUTH_WRITE) {
						authorized = true;
					}
					break;
				default:
					break;
				}

				if (authorized) {
					this.state = EXPRESS_STATE_ATTR;
				}
				else {
					this.state = EXPRESS_STATE_UNAUTH;
				}
				this.monitor.notify();
			}
			else {
				this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
				if (null != this.listener) {
					this.listener.expressError(this.context);
				}

				this.state = EXPRESS_STATE_EXIT;
				this.monitor.notify();
			}
		}
	}

	@Override
	public void sessionCreated(Session session) {
	}

	@Override
	public void sessionDestroyed(Session session) {
		synchronized (this.monitor) {
			this.state = EXPRESS_STATE_LOST;
			this.monitor.notify();
		}
	}

	@Override
	public void sessionOpened(Session session) {
		synchronized (this.monitor) {
			this.monitor.notify();
		}
	}

	@Override
	public void sessionClosed(Session session) {
	}

	@Override
	public void messageReceived(Session session, Message message) {
		Packet packet = Packet.unpack(message.get());
		if (null != packet) {
			interpret(session, packet);
		}
		else {
			this.context.errorCode = FileExpressContext.EC_PACKET_ERROR;
			if (null != this.listener) {
				this.listener.expressError(this.context);
			}
		}
	}

	@Override
	public void messageSent(Session session, Message message) {
	}

	@Override
	public void errorOccurred(int errorCode, Session session) {
//		System.out.println("errorOccurred:" + errorCode);
		this.context.errorCode = FileExpressContext.EC_NETWORK_FAULT;
		if (null != this.listener) {
			this.listener.expressError(this.context);
		}

		synchronized (this.monitor) {
			this.state = EXPRESS_STATE_EXIT;
			this.monitor.notify();
		}
	}
}
