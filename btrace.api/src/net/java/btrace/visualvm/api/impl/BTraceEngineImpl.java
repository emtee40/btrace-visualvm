/*
 * Copyright 2007-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */
package net.java.btrace.visualvm.api.impl;

import com.sun.btrace.CommandListener;
import com.sun.btrace.client.Client;
import com.sun.btrace.comm.Command;
import com.sun.btrace.comm.RetransformationStartNotification;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.java.btrace.visualvm.api.BTraceEngine;
import net.java.btrace.visualvm.api.BTraceSettings;
import net.java.btrace.visualvm.api.BTraceTask;
import net.java.btrace.visualvm.api.compiler.BCompiler;
import net.java.btrace.visualvm.spi.ProcessDetailsProvider;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;

/**
 *
 * @author Jaroslav Bachorik
 */
public class BTraceEngineImpl extends BTraceEngine {
    final private static Logger LOGGER = Logger.getLogger(BTraceEngineImpl.class.getName());

    private String clientPath;
    private String agentPath;
    private Map<BTraceTask, Client> clientMap = new HashMap<BTraceTask, Client>();

    final private Set<StateListener> listeners = new HashSet<StateListener>();

    public BTraceEngineImpl() {
        File locatedFile = InstalledFileLocator.getDefault().locate("modules/ext/btrace-agent.jar", "com.sun.btrace", false); // NOI18N
        agentPath = locatedFile.getAbsolutePath();

        locatedFile = InstalledFileLocator.getDefault().locate("modules/ext/btrace-client.jar", "com.sun.btrace", false); // NOI18N
        clientPath = locatedFile.getAbsolutePath();
    }

    @Override
    public BTraceTask createTask(int pid) {
        if (getProcessDetailsProvider().canBeTraced(pid)) {
            return new BTraceTaskImpl(pid, this);
        }
        return null;
    }

    @Override
    public void addListener(StateListener listener) {
        synchronized(listeners) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(StateListener listener) {
        synchronized(listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public boolean start(final BTraceTask task) {
        LOGGER.finest("Starting BTrace task");

        boolean result = doStart(task);
        LOGGER.log(Level.FINEST, "BTrace task {0}", result ? "started successfuly" : "failed");
        if (result) {
            fireOnTaskStart(task);
        }
        return result;
    }

    final private AtomicBoolean stopping = new AtomicBoolean(false);
    @Override
    public boolean stop(final BTraceTask task) {
        LOGGER.finest("Attempting to stop BTrace task");
        try {
            if (stopping.compareAndSet(false, true)) {
                LOGGER.finest("Stopping BTrace task");
                boolean result = doStop(task);
                LOGGER.log(Level.FINEST, "BTrace task {0}", result ? "stopped successfuly" : "not stopped");
                if (result) {
                    fireOnTaskStop(task);
                }
                return result;
            }
            return true;
        } finally {
            stopping.set(false);
        }
    }

    private boolean doStart(BTraceTask task) {
        final AtomicBoolean result = new AtomicBoolean(false);
        final BTraceTaskImpl btrace = (BTraceTaskImpl) task;
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String toolsJarCp = findToolsJarPath(btrace.getSystemProperties());
            LOGGER.log(Level.FINEST, "tools.jar located at {0}", toolsJarCp);
            BCompiler compiler = new BCompiler(btrace.isUnsafe(), clientPath, toolsJarCp);
            btrace.setState(BTraceTask.State.COMPILING);
            final byte[] bytecode = compiler.compile(btrace.getScript(), task.getClassPath(), btrace.getWriter());
            btrace.setState(BTraceTask.State.COMPILED);
            
            LOGGER.log(Level.FINEST, "Compiled the trace: {0} bytes", bytecode.length);
            RequestProcessor.getDefault().post(new Runnable() {

                public void run() {
                    String portStr = btrace.getSystemProperties().getProperty("btrace.port"); // I
                    LOGGER.log(Level.FINEST, "BTrace agent listening on port {0}", portStr);
                    Client existingClient = clientMap.get(btrace);
                    final Client client = existingClient != null ? existingClient : new Client(portStr != null ? Integer.parseInt(portStr) : findFreePort(), ".", BTraceSettings.sharedInstance().isDebugMode(), true, btrace.isUnsafe(),  BTraceSettings.sharedInstance().isDumpClasses(), BTraceSettings.sharedInstance().getDumpClassPath());
                    
                    try {
                        client.attach(String.valueOf(btrace.getPid()), agentPath, toolsJarCp, null);
                        Thread.sleep(200); // give the server side time to initialize and open the port
                        client.submit(bytecode, new String[]{}, new CommandListener() {
                            private boolean retransforming = false;
                            public void onCommand(Command cmd) throws IOException {
                                LOGGER.log(Level.FINEST, "Received command: {0}", cmd.toString());
                                switch (cmd.getType()) {
                                    case Command.SUCCESS: {
                                        if (retransforming) {
                                            clientMap.put(btrace, client);
                                            result.set(true);
                                            latch.countDown();

                                            retransforming = false;
                                        }
                                        break;
                                    }
                                    case Command.EXIT: {
                                        latch.countDown();
                                        stop(btrace);
                                        break;
                                    }
                                    case Command.RETRANSFORMATION_START: {
                                        int numClasses = ((RetransformationStartNotification)cmd).getNumClasses();
                                        btrace.setInstrClasses(numClasses);
                                        btrace.setState(BTraceTask.State.INSTRUMENTING);
                                        retransforming = true;
                                        break;
                                    }
                                }
                                btrace.dispatchCommand(cmd);
                            }
                        });
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, e.getLocalizedMessage(), e);
                        result.set(false);
                        latch.countDown();
                    }
                }
            });
            latch.await();

        } catch (InterruptedException ex) {
            Exceptions.printStackTrace(ex);
        } catch (InstantiationException ex) {
            Exceptions.printStackTrace(ex);
        }
        return result.get();
    }

    private boolean doStop(BTraceTask task) {
        Client client = clientMap.get(task);
        if (client != null) {
            try {
                client.sendExit(0);
                Thread.sleep(300);
                client.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                // ignore all IO related exception during the stop sequence
            }
        }
        return true;
    }

    @Override
    public void sendEvent(BTraceTaskImpl task) {
        Client client = clientMap.get(task);
        if (client != null) {
            try {
                client.sendEvent();
            } catch (IOException ex) {
                // TODO
                Exceptions.printStackTrace(ex);
            }
        }
    }

    @Override
    public void sendEvent(BTraceTaskImpl task, String eventName) {
        Client client = clientMap.get(task);
        if (client != null) {
            try {
                client.sendEvent(eventName);
            } catch (IOException ex) {
                // TODO
                Exceptions.printStackTrace(ex);
            }
        }
    }

    public static int findFreePort() {
        ServerSocket server = null;
        int port = 0;
        try {
            server = new ServerSocket(0);
            port = server.getLocalPort();
        } catch (IOException iOException) {
            port = 3456;
        } finally {
            try {
                server.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return port;
    }

    private static String findToolsJarPath(Properties props) {
        String toolsJarPath = null;

        if (props != null && props.containsKey("java.home")) {
            if(props.getProperty("os.name").startsWith("Mac")) {
                String java_home = props.getProperty("java.home");
                String java_mac_home = java_home.substring(0,java_home.indexOf("/Home"));
                toolsJarPath = java_mac_home + "/Classes/classes.jar";
            } else {
                String java_home = props.getProperty("java.home");
                java_home = java_home.replace(File.separator + "jre", "");
                toolsJarPath = java_home + "/lib/tools.jar";
            }
        }

        if (!new File(toolsJarPath).exists()) {
            // may be the target app is running on a JRE. Let us hope
            // VisualVM is running on a JDK!
            if(System.getProperty("os.name").startsWith("Mac")) {
                String java_home = System.getProperty("java.home");
                String java_mac_home = java_home.substring(0,java_home.indexOf("/Home"));
                toolsJarPath = java_mac_home + "/Classes/classes.jar";
            } else {
                String java_home = System.getProperty("java.home");
                java_home = java_home.replace(File.separator + "jre", "");
                toolsJarPath = java_home + "/lib/tools.jar";
            }
        }
        return toolsJarPath;
    }

    final private ProcessDetailsProvider getProcessDetailsProvider() {
        ProcessDetailsProvider pdp = Lookup.getDefault().lookup(ProcessDetailsProvider.class);
        return pdp != null ? pdp : ProcessDetailsProvider.NULL;
    }

    private void fireOnTaskStart(BTraceTask task) {
        synchronized(listeners) {
            for(StateListener listener : listeners) {
                listener.onTaskStart(task);
            }
        }
    }

    private void fireOnTaskStop(BTraceTask task) {
        synchronized(listeners) {
            for(StateListener listener : listeners) {
                listener.onTaskStop(task);
            }
        }
    }
}