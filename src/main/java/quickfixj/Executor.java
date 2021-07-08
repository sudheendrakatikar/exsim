package quickfixj;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.management.JMException;
import javax.management.ObjectName;

import org.quickfixj.jmx.JmxExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldConvertError;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.ScreenLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider.TemplateMapping;

import static quickfix.Acceptor.SETTING_ACCEPTOR_TEMPLATE;
import static quickfix.Acceptor.SETTING_SOCKET_ACCEPT_ADDRESS;
import static quickfix.Acceptor.SETTING_SOCKET_ACCEPT_PORT;

public class Executor {

	private final static Logger log = LoggerFactory.getLogger(Executor.class);
	private final SocketAcceptor acceptor;
	private final Map<InetSocketAddress, List<TemplateMapping>> dynamicSessionMappings = new HashMap<InetSocketAddress, List<TemplateMapping>>();

	private final JmxExporter jmxExporter;
	private final ObjectName connectorObjectName;

	public Executor(SessionSettings settings) throws ConfigError, FieldConvertError, JMException {
		ExecutorApplication application = new ExecutorApplication(settings);
		MessageStoreFactory messageStoreFactory = new FileStoreFactory(settings);
		LogFactory logFactory = new ScreenLogFactory(true, true, true);
		MessageFactory messageFactory = new DefaultMessageFactory();

		acceptor = new SocketAcceptor(application, messageStoreFactory, settings, logFactory, messageFactory);

		configureDynamicSessions(settings, application, messageStoreFactory, logFactory, messageFactory);

		jmxExporter = new JmxExporter();
		connectorObjectName = jmxExporter.register(acceptor);
		log.info("Acceptor registered with JMX, name={}", connectorObjectName);
	}

	private void configureDynamicSessions(SessionSettings settings, ExecutorApplication application,
			MessageStoreFactory messageStoreFactory, LogFactory logFactory, MessageFactory messageFactory)
			throws ConfigError, FieldConvertError {
		//
		// If a session template is detected in the settings, then
		// set up a dynamic session provider.
		//

		Iterator<SessionID> sectionIterator = settings.sectionIterator();
		while (sectionIterator.hasNext()) {
			SessionID sessionID = sectionIterator.next();
			if (isSessionTemplate(settings, sessionID)) {
				InetSocketAddress address = getAcceptorSocketAddress(settings, sessionID);
				getMappings(address).add(new TemplateMapping(sessionID, sessionID));
			}
		}

		for (Map.Entry<InetSocketAddress, List<TemplateMapping>> entry : dynamicSessionMappings.entrySet()) {
			acceptor.setSessionProvider(entry.getKey(), new DynamicAcceptorSessionProvider(settings, entry.getValue(),
					application, messageStoreFactory, logFactory, messageFactory));
		}
	}

	private List<TemplateMapping> getMappings(InetSocketAddress address) {
		return dynamicSessionMappings.computeIfAbsent(address, k -> new ArrayList<>());
	}

	private InetSocketAddress getAcceptorSocketAddress(SessionSettings settings, SessionID sessionID)
			throws ConfigError, FieldConvertError {
		String acceptorHost = "0.0.0.0";
		if (settings.isSetting(sessionID, SETTING_SOCKET_ACCEPT_ADDRESS)) {
			acceptorHost = settings.getString(sessionID, SETTING_SOCKET_ACCEPT_ADDRESS);
		}
		int acceptorPort = (int) settings.getLong(sessionID, SETTING_SOCKET_ACCEPT_PORT);

		return new InetSocketAddress(acceptorHost, acceptorPort);
	}

	private boolean isSessionTemplate(SessionSettings settings, SessionID sessionID)
			throws ConfigError, FieldConvertError {
		return settings.isSetting(sessionID, SETTING_ACCEPTOR_TEMPLATE)
				&& settings.getBool(sessionID, SETTING_ACCEPTOR_TEMPLATE);
	}

	private void start() throws RuntimeError, ConfigError {
		acceptor.start();
	}

	private void stop() {
		try {
			jmxExporter.getMBeanServer().unregisterMBean(connectorObjectName);
		} catch (Exception e) {
			log.error("Failed to unregister acceptor from JMX", e);
		}
		acceptor.stop();
	}

	public static void main(String[] args) throws Exception {
		try {
			InputStream inputStream = getSettingsInputStream(args);
			SessionSettings settings = new SessionSettings(inputStream);
			inputStream.close();

			Executor executor = new Executor(settings);
			executor.start();

			System.out.println("press <enter> to quit");
			System.in.read();

			executor.stop();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private static InputStream getSettingsInputStream(String[] args) throws FileNotFoundException {
		InputStream inputStream = null;
		if (args.length == 0) {
			inputStream = Executor.class.getResourceAsStream("/quickfixj/src/main/resources/executor.cfg");
		} else if (args.length == 1) {
			inputStream = new FileInputStream(args[0]);
		}
		if (inputStream == null) {
			System.out.println("usage: " + Executor.class.getName() + " [configFile].");
			System.exit(1);
		}
		return inputStream;
	}

}
