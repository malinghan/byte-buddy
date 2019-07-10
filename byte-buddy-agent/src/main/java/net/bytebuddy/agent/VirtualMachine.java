/*
 * Copyright 2014 - 2019 Rafael Winterhalter
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
 */
package net.bytebuddy.agent;

import com.sun.jna.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.*;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * An implementation for attachment on a virtual machine. This interface mimics the tooling API's virtual
 * machine interface to allow for similar usage by {@link ByteBuddyAgent} where all calls are made via
 * reflection such that this structural typing suffices for interoperability.
 * </p>
 * <p>
 * <b>Note</b>: Implementations are required to declare a static method {@code attach(String)} returning an
 * instance of a class that declares the methods defined by {@link VirtualMachine}.
 * </p>
 */
public interface VirtualMachine {

    /**
     * Loads an agent into the represented virtual machine.
     *
     * @param jarFile  The jar file to attach.
     * @param argument The argument to provide or {@code null} if no argument should be provided.
     * @throws IOException If an I/O exception occurs.
     */
    void loadAgent(String jarFile, String argument) throws IOException;

    /**
     * Loads a native agent into the represented virtual machine.
     *
     * @param library  The agent library.
     * @param argument The argument to provide or {@code null} if no argument should be provided.
     * @throws IOException If an I/O exception occurs.
     */
    void loadAgentPath(String library, String argument) throws IOException;

    /**
     * Detaches this virtual machine representation.
     *
     * @throws IOException If an I/O exception occurs.
     */
    void detach() throws IOException;

    /**
     * A virtual machine implementation for a HotSpot VM or any compatible VM.
     */
    class ForHotSpotVm implements VirtualMachine {

        /**
         * The UTF-8 charset name.
         */
        private static final String UTF_8 = "UTF-8";

        /**
         * The protocol version to use for communication.
         */
        private static final String PROTOCOL_VERSION = "1";

        /**
         * The {@code load} command.
         */
        private static final String LOAD_COMMAND = "load";

        /**
         * The {@code instrument} command.
         */
        private static final String INSTRUMENT_COMMAND = "instrument";

        /**
         * A delimiter to be used for attachment.
         */
        private static final String ARGUMENT_DELIMITER = "=";

        /**
         * A blank line argument.
         */
        private static final byte[] BLANK = new byte[]{0};

        /**
         * The virtual machine connection.
         */
        private final Connection connection;

        /**
         * Creates a default virtual machine implementation.
         *
         * @param connection The virtual machine connection.
         */
        protected ForHotSpotVm(Connection connection) {
            this.connection = connection;
        }

        /**
         * Asserts if this virtual machine type is available on the current VM.
         *
         * @return The virtual machine type if available.
         * @throws Throwable If this virtual machine is not available.
         */
        public static Class<?> assertAvailability() throws Throwable {
            if (Platform.isWindows() || Platform.isWindowsCE()) {
                throw new IllegalStateException("POSIX sockets are not available on Windows");
            } else {
                Class.forName(Native.class.getName()); // Attempt loading the JNA class to check availability.
                return ForHotSpotVm.class;
            }
        }

        /**
         * Attaches to the supplied process id using the default JNA implementation.
         *
         * @param processId The process id.
         * @return A suitable virtual machine implementation.
         * @throws IOException If an IO exception occurs during establishing the connection.
         */
        public static VirtualMachine attach(String processId) throws IOException {
            return attach(processId, new Connection.ForJnaPosixSocket.Factory(10, 100, TimeUnit.MILLISECONDS));
        }

        /**
         * Attaches to the supplied process id using the supplied connection factory.
         *
         * @param processId         The process id.
         * @param connectionFactory The connection factory to use.
         * @return A suitable virtual machine implementation.
         * @throws IOException If an IO exception occurs during establishing the connection.
         */
        public static VirtualMachine attach(String processId, Connection.Factory connectionFactory) throws IOException {
            return new ForHotSpotVm(connectionFactory.connect(processId));
        }

        /**
         * {@inheritDoc}
         */
        public void loadAgent(String jarFile, String argument) throws IOException {
            load(jarFile, false, argument);
        }

        /**
         * {@inheritDoc}
         */
        public void loadAgentPath(String library, String argument) throws IOException {
            load(library, true, argument);
        }

        /**
         * Loads an agent by the given command.
         *
         * @param file     The Java agent or library to be loaded.
         * @param isNative {@code true} if the agent is native.
         * @param argument The argument to the agent or {@code null} if no argument is given.
         * @throws IOException If an I/O exception occurs.
         */
        protected void load(String file, boolean isNative, String argument) throws IOException {
            connection.write(PROTOCOL_VERSION.getBytes(UTF_8));
            connection.write(BLANK);
            connection.write(LOAD_COMMAND.getBytes(UTF_8));
            connection.write(BLANK);
            connection.write(INSTRUMENT_COMMAND.getBytes(UTF_8));
            connection.write(BLANK);
            connection.write(Boolean.toString(isNative).getBytes(UTF_8));
            connection.write(BLANK);
            connection.write((argument == null
                    ? file
                    : file + ARGUMENT_DELIMITER + argument).getBytes(UTF_8));
            connection.write(BLANK);
            byte[] buffer = new byte[1];
            StringBuilder stringBuilder = new StringBuilder();
            int length;
            while ((length = connection.read(buffer)) != -1) {
                if (length > 0) {
                    if (buffer[0] == 10) {
                        break;
                    }
                    stringBuilder.append((char) buffer[0]);
                }
            }
            switch (Integer.parseInt(stringBuilder.toString())) {
                case 0:
                    return;
                case 101:
                    throw new IOException("Protocol mismatch with target VM");
                default:
                    buffer = new byte[1024];
                    stringBuilder = new StringBuilder();
                    while ((length = connection.read(buffer)) != -1) {
                        stringBuilder.append(new String(buffer, 0, length, UTF_8));
                    }
                    throw new IllegalStateException(stringBuilder.toString());
            }
        }

        /**
         * {@inheritDoc}
         */
        public void detach() throws IOException {
            connection.close();
        }

        /**
         * Represents a connection to a virtual machine.
         */
        public interface Connection extends Closeable {

            /**
             * Reads from the connected virtual machine.
             *
             * @param buffer The buffer to read from.
             * @return The amount of bytes that were read.
             * @throws IOException If an I/O exception occurs during reading.
             */
            int read(byte[] buffer) throws IOException;

            /**
             * Writes to the connected virtual machine.
             *
             * @param buffer The buffer to write to.
             * @throws IOException If an I/O exception occurs during writing.
             */
            void write(byte[] buffer) throws IOException;

            /**
             * A factory for creating connections to virtual machines.
             */
            interface Factory {

                /**
                 * Connects to the supplied process.
                 *
                 * @param processId The process id.
                 * @return The connection to the virtual machine with the supplied process id.
                 * @throws IOException If an I/O exception occurs during connecting to the targeted VM.
                 */
                Connection connect(String processId) throws IOException;

                /**
                 * A factory for attaching on a POSIX-compatible VM.
                 */
                abstract class ForPosixSocket implements Factory {

                    /**
                     * The temporary directory on Unix systems.
                     */
                    private static final String TEMPORARY_DIRECTORY = "/tmp";

                    /**
                     * The name prefix for a socket.
                     */
                    private static final String SOCKET_FILE_PREFIX = ".java_pid";

                    /**
                     * The name prefix for an attachment file indicator.
                     */
                    private static final String ATTACH_FILE_PREFIX = ".attach_pid";

                    /**
                     * The maximum amount of attempts to establish a POSIX socket connection to the target VM.
                     */
                    private final int attempts;

                    /**
                     * The pause between two checks for an existing socket.
                     */
                    private final long pause;

                    /**
                     * The time unit of the pause time.
                     */
                    private final TimeUnit timeUnit;

                    /**
                     * Creates a new connection factory for creating a connection to a JVM via a POSIX socket.
                     *
                     * @param attempts The maximum amount of attempts to establish a POSIX socket connection to the target VM.
                     * @param pause    The pause between two checks for an existing socket.
                     * @param timeUnit The time unit of the pause time.
                     */
                    protected ForPosixSocket(int attempts, long pause, TimeUnit timeUnit) {
                        this.attempts = attempts;
                        this.pause = pause;
                        this.timeUnit = timeUnit;
                    }

                    /**
                     * {@inheritDoc}
                     */
                    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "File name convention is specified.")
                    public Connection connect(String processId) throws IOException {
                        File socket = new File(TEMPORARY_DIRECTORY, SOCKET_FILE_PREFIX + processId);
                        if (!socket.exists()) {
                            String target = ATTACH_FILE_PREFIX + processId, path = "/proc/" + processId + "/cwd/" + target;
                            File attachFile = new File(path);
                            try {
                                if (!attachFile.createNewFile() && !attachFile.isFile()) {
                                    throw new IllegalStateException("Could not create attach file: " + attachFile);
                                }
                            } catch (IOException ignored) {
                                attachFile = new File(TEMPORARY_DIRECTORY, target);
                                if (!attachFile.createNewFile() && !attachFile.isFile()) {
                                    throw new IllegalStateException("Could not create attach file: " + attachFile);
                                }
                            }
                            try {
                                // The HotSpot attachment API attempts to send the signal to all children of a process
                                Process process = Runtime.getRuntime().exec("kill -3 " + processId);
                                int attempts = this.attempts;
                                boolean killed = false;
                                do {
                                    try {
                                        if (process.exitValue() != 0) {
                                            throw new IllegalStateException("Error while sending signal to target VM: " + processId);
                                        }
                                        killed = true;
                                        break;
                                    } catch (IllegalThreadStateException ignored) {
                                        attempts -= 1;
                                        Thread.sleep(timeUnit.toMillis(pause));
                                    }
                                } while (attempts > 0);
                                if (!killed) {
                                    throw new IllegalStateException("Target VM did not respond to signal: " + processId);
                                }
                                attempts = this.attempts;
                                while (attempts-- > 0 && !socket.exists()) {
                                    Thread.sleep(timeUnit.toMillis(pause));
                                }
                                if (!socket.exists()) {
                                    throw new IllegalStateException("Target VM did not respond: " + processId);
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Interrupted during wait for process", exception);
                            } finally {
                                if (!attachFile.delete()) {
                                    attachFile.deleteOnExit();
                                }
                            }
                        }
                        return doConnect(socket);
                    }

                    /**
                     * Connects to the supplied POSIX socket.
                     *
                     * @param socket The socket to connect to.
                     * @return An active connection to the supplied socket.
                     * @throws IOException If an error occurs during connection.
                     */
                    protected abstract Connection doConnect(File socket) throws IOException;
                }
            }

            /**
             * Implements a connection for a Posix socket in JNA.
             */
            class ForJnaPosixSocket implements Connection {

                /**
                 * The JNA library to use.
                 */
                private final PosixLibrary library;

                /**
                 * The socket's handle.
                 */
                private final int handle;

                /**
                 * Creates a new connection for a Posix socket.
                 *
                 * @param library The JNA library to use.
                 * @param handle  The socket's handle.
                 */
                protected ForJnaPosixSocket(PosixLibrary library, int handle) {
                    this.library = library;
                    this.handle = handle;
                }

                /**
                 * {@inheritDoc}
                 */
                public int read(byte[] buffer) {
                    return library.read(handle, ByteBuffer.wrap(buffer), buffer.length);
                }

                /**
                 * {@inheritDoc}
                 */
                public void write(byte[] buffer) {
                    int write = library.write(handle, ByteBuffer.wrap(buffer), buffer.length);
                    if (write != buffer.length) {
                        throw new IllegalStateException("Could not write entire buffer to socket");
                    }
                }

                /**
                 * {@inheritDoc}
                 */
                public void close() {
                    library.close(handle);
                }

                /**
                 * A JNA library binding for Posix sockets.
                 */
                protected interface PosixLibrary extends Library {

                    /**
                     * Creates a POSIX socket connection.
                     *
                     * @param domain   The socket's domain.
                     * @param type     The socket's type.
                     * @param protocol The protocol version.
                     * @return A handle to the socket that was created or {@code 0} if no socket could be created.
                     */
                    int socket(int domain, int type, int protocol);

                    /**
                     * Connects a socket connection.
                     *
                     * @param handle  The socket's handle.
                     * @param address The address of the POSIX socket.
                     * @param length  The length of the socket value.
                     * @return {@code 0} if the socket was connected or an error code.
                     */
                    int connect(int handle, SocketAddress address, int length);

                    /**
                     * Reads from a POSIX socket.
                     *
                     * @param handle The socket's handle.
                     * @param buffer The buffer to read from.
                     * @param count  The bytes being read.
                     * @return The amount of bytes that could be read.
                     */
                    int read(int handle, ByteBuffer buffer, int count);

                    /**
                     * Writes to a POSIX socket.
                     *
                     * @param handle The socket's handle.
                     * @param buffer The buffer to write to.
                     * @param count  The bytes being written.
                     * @return The amount of bytes that could be written.
                     */
                    int write(int handle, ByteBuffer buffer, int count);

                    /**
                     * Closes the socket connection.
                     *
                     * @param handle The handle of the connection.
                     * @return {@code 0} if the socket was closed or an error code.
                     */
                    int close(int handle);

                    /**
                     * Represents an address for a POSIX socket.
                     */
                    class SocketAddress extends Structure {

                        /**
                         * The socket family.
                         */
                        @SuppressWarnings("unused")
                        @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Field required by native implementation.")
                        public short family = 1;

                        /**
                         * The socket path.
                         */
                        public byte[] path = new byte[100];

                        /**
                         * Sets the socket path.
                         *
                         * @param path The socket path.
                         */
                        public void setPath(String path) {
                            try {
                                System.arraycopy(path.getBytes("UTF-8"), 0, this.path, 0, path.length());
                                System.arraycopy(new byte[]{0}, 0, this.path, path.length(), 1);
                            } catch (UnsupportedEncodingException exception) {
                                throw new IllegalStateException(exception);
                            }
                        }

                        @Override
                        protected List<String> getFieldOrder() {
                            return Arrays.asList("family", "path");
                        }
                    }
                }

                /**
                 * A factory for a POSIX socket connection to a JVM using JNA.
                 */
                public static class Factory extends Connection.Factory.ForPosixSocket {

                    /**
                     * The socket library API.
                     */
                    private final PosixLibrary library;

                    /**
                     * Creates a new connection factory for creating a connection to a JVM via a POSIX socket using JNA.
                     *
                     * @param attempts The maximum amount of attempts to establish a POSIX socket connection to the target VM.
                     * @param pause    The pause between two checks for an existing socket.
                     * @param timeUnit The time unit of the pause time.
                     */
                    public Factory(int attempts, long pause, TimeUnit timeUnit) {
                        super(attempts, pause, timeUnit);
                        library = Native.load("c", PosixLibrary.class);
                    }

                    /**
                     * {@inheritDoc}
                     */
                    public Connection doConnect(File socket) {
                        int handle = library.socket(1, 1, 0);
                        if (handle == 0) {
                            throw new IllegalStateException("Could not open POSIX socket");
                        }
                        PosixLibrary.SocketAddress address = new PosixLibrary.SocketAddress();
                        address.setPath(socket.getAbsolutePath());
                        if (library.connect(handle, address, address.size()) != 0) {
                            throw new IllegalStateException("Could not connect to POSIX socket on " + socket);
                        }
                        return new Connection.ForJnaPosixSocket(library, handle);
                    }
                }
            }
        }
    }

    class ForOpenJ9Vm implements VirtualMachine {

        private static final String IBM_TEMPORARY_FOLDER = "com.ibm.tools.attach.directory";

        private final Socket socket;

        protected ForOpenJ9Vm(Socket socket) {
            this.socket = socket;
        }

        public static VirtualMachine attach(String processId) throws IOException {
            return attach(processId, 5000, new Connector.ForJnaPosixEnvironment());
        }

        public static VirtualMachine attach(String processId, int timeout, Connector connector) throws IOException {
            File directory = new File(System.getProperty(IBM_TEMPORARY_FOLDER, connector.getTemporaryFolder()), ".com_ibm_tools_attach");
            RandomAccessFile attachLock = new RandomAccessFile(new File(directory, "_attachlock"), "rw");
            try {
                FileLock attachLockLock = attachLock.getChannel().lock();
                try {
                    List<Properties> virtualMachines;
                    RandomAccessFile master = new RandomAccessFile(new File(directory, "_master"), "rw");
                    try {
                        FileLock masterLock = master.getChannel().lock();
                        try {
                            File[] vmFolder = directory.listFiles();
                            if (vmFolder == null) {
                                throw new IllegalStateException("No descriptor files found in " + directory);
                            }
                            long userId = connector.userId();
                            virtualMachines = new ArrayList<Properties>();
                            for (File aVmFolder : vmFolder) {
                                if (aVmFolder.isDirectory() && (userId == 0 || connector.getOwnerOf(aVmFolder) == userId)) {
                                    File attachInfo = new File(aVmFolder, "attachInfo");
                                    if (attachInfo.isFile()) {
                                        Properties virtualMachine = new Properties();
                                        FileInputStream inputStream = new FileInputStream(attachInfo);
                                        try {
                                            virtualMachine.load(inputStream);
                                        } finally {
                                            inputStream.close();
                                        }
                                        long targetProcessId = Long.parseLong(virtualMachine.getProperty("processId"));
                                        long targetUserId;
                                        try {
                                            targetUserId = Long.parseLong(virtualMachine.getProperty("userUid"));
                                        } catch (NumberFormatException ignored) {
                                            targetUserId = 0L;
                                        }
                                        if (userId != 0L && targetProcessId == 0L) {
                                            targetUserId = connector.getOwnerOf(attachInfo);
                                        }
                                        if (targetProcessId == 0L || connector.isExistingProcess(targetProcessId)) {
                                            virtualMachines.add(virtualMachine);
                                        } else if (userId == 0L || targetUserId == userId) {
                                            File[] vmFile = aVmFolder.listFiles();
                                            if (vmFile != null) {
                                                for (File aVmFile : vmFile) {
                                                    if (!aVmFile.delete()) {
                                                        aVmFile.deleteOnExit();
                                                    }
                                                }
                                            }
                                            if (!aVmFolder.delete()) {
                                                aVmFolder.deleteOnExit();
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            masterLock.release();
                        }
                    } finally {
                        master.close();
                    }
                    Properties target = null;
                    for (Properties virtualMachine : virtualMachines) {
                        if (virtualMachine.getProperty("processId").equalsIgnoreCase(processId)) {
                            target = virtualMachine;
                            break;
                        }
                    }
                    if (target == null) {
                        throw new IllegalStateException("Could not locate target process info in " + directory);
                    }
                    ServerSocket serverSocket = new ServerSocket(0);
                    serverSocket.setSoTimeout(timeout);
                    try {
                        File receiver = new File(directory, target.getProperty("vmId"));
                        String key = Long.toHexString(new SecureRandom().nextLong());
                        File reply = new File(receiver, "replyInfo");
                        try {
                            if (reply.createNewFile()) {
                                connector.setPermissions(reply, 0600);
                            }
                            FileOutputStream outputStream = new FileOutputStream(reply);
                            try {
                                outputStream.write(key.getBytes("UTF-8"));
                                outputStream.write("\n".getBytes("UTF-8"));
                                outputStream.write(Long.toString(serverSocket.getLocalPort()).getBytes("UTF-8"));
                                outputStream.write("\n".getBytes("UTF-8"));
                            } finally {
                                outputStream.close();
                            }
                            Map<RandomAccessFile, FileLock> locks = new HashMap<RandomAccessFile, FileLock>();
                            try {
                                String pid = Long.toString(connector.pid());
                                for (Properties virtualMachine : virtualMachines) {
                                    if (!virtualMachine.getProperty("processId").equalsIgnoreCase(pid)) {
                                        String attachNotificationSync = virtualMachine.getProperty("attachNotificationSync");
                                        RandomAccessFile syncFile = new RandomAccessFile(attachNotificationSync == null
                                                ? new File(directory, "attachNotificationSync")
                                                : new File(attachNotificationSync), "rw");
                                        try {
                                            locks.put(syncFile, syncFile.getChannel().lock());
                                        } catch (IOException ignored) {
                                            syncFile.close();
                                        }
                                    }
                                }
                                int notifications = 0;
                                File[] item = directory.listFiles();
                                if (item != null) {
                                    for (File anItem : item) {
                                        String name = anItem.getName();
                                        if (!name.startsWith(".trash_")
                                                && !name.equalsIgnoreCase("_attachlock")
                                                && !name.equalsIgnoreCase("_master")
                                                && !name.equalsIgnoreCase("_notifier")) {
                                            notifications += 1;
                                        }
                                    }
                                }
                                connector.incrementSemaphore(directory, "_notifier", notifications);
                                try {
                                    Socket socket = serverSocket.accept();
                                    String answer = read(socket);
                                    if (answer.contains(' ' + key + ' ')) {
                                        return new ForOpenJ9Vm(socket);
                                    } else {
                                        throw new IllegalStateException("Unexpected answered to attachment: " + answer);
                                    }
                                } finally {
                                    connector.decrementSemaphore(directory, "_notifier", notifications);
                                }
                            } finally {
                                for (Map.Entry<RandomAccessFile, FileLock> entry : locks.entrySet()) {
                                    try {
                                        try {
                                            entry.getValue().release();
                                        } finally {
                                            entry.getKey().close();
                                        }
                                    } catch (Throwable ignored) {
                                        /* do nothing */
                                    }
                                }
                            }
                        } finally {
                            if (!reply.delete()) {
                                reply.deleteOnExit();
                            }
                        }
                    } finally {
                        serverSocket.close();
                    }
                } finally {
                    attachLockLock.release();
                }
            } finally {
                attachLock.close();
            }
        }

        public void loadAgent(String jarFile, String argument) throws IOException {
            write(socket, "ATTACH_LOADAGENT(instrument," + jarFile + '=' + (argument == null ? "" : argument) + ')');
            String answer = read(socket);
            if (answer.startsWith("ATTACH_ERR")) {
                throw new IllegalStateException("Target agent failed loading agent: " + answer);
            } else if (!answer.startsWith("ATTACH_ACK") && !answer.startsWith("ATTACH_RESULT=")) {
                throw new IllegalStateException("Unexpected response: " + answer);
            }
        }

        public void loadAgentPath(String library, String argument) throws IOException {
            write(socket, "ATTACH_LOADAGENTPATH(" + library + (argument == null ? "" : (',' + argument)) + ')');
            String answer = read(socket);
            if (answer.startsWith("ATTACH_ERR")) {
                throw new IllegalStateException("Target agent failed loading library: " + answer);
            } else if (!answer.startsWith("ATTACH_ACK") && !answer.startsWith("ATTACH_RESULT=")) {
                throw new IllegalStateException("Unexpected response: " + answer);
            }
        }

        public void detach() throws IOException {
            try {
                write(socket, "ATTACH_DETACH");
                read(socket); // The answer is intentionally ignored.
            } finally {
                socket.close();
            }
        }

        private static void write(Socket socket, String value) throws IOException {
            socket.getOutputStream().write(value.getBytes("UTF-8"));
            socket.getOutputStream().write(0);
            socket.getOutputStream().flush();
        }

        private static String read(Socket socket) throws IOException {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = socket.getInputStream().read(buffer)) != -1) {
                if (length > 0 && buffer[length - 1] == 0) {
                    outputStream.write(buffer, 0, length - 1);
                    break;
                } else {
                    outputStream.write(buffer, 0, length);
                }
            }
            return outputStream.toString("UTF-8");
        }

        public interface Connector {

            String getTemporaryFolder();

            long pid();

            long userId();

            boolean isExistingProcess(long targetProcessId);

            long getOwnerOf(File folder);

            void setPermissions(File reply, int permissions);

            void incrementSemaphore(File directory, String name, int notifications);

            void decrementSemaphore(File directory, String name, int notifications);

            class ForJnaPosixEnvironment implements Connector {

                private static final int O_CREAT = 0x40;

                private static final int ESRCH = 3;

                private final PosixLibrary library = Native.load("c", PosixLibrary.class);

                public String getTemporaryFolder() {
                    return "/tmp";
                }

                public long pid() {
                    return library.getpid();
                }

                public long userId() {
                    return library.getuid();
                }

                public boolean isExistingProcess(long processId) {
                    return library.kill(processId, 0) != ESRCH;
                }

                public long getOwnerOf(File folder) {
                    // TODO: Is there an easy way of getting the uid of the file owner?
                    try {
                        Method method = Class
                                .forName("com.ibm.tools.attach.target.CommonDirectory")
                                .getMethod("getFileOwner", String.class);
                        return (Long) method.invoke(null, folder.getAbsolutePath());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                public void setPermissions(File reply, int permissions) {
                    library.chmod(reply.getAbsolutePath(), permissions);
                }

                public void incrementSemaphore(File directory, String name, int count) {
                    // TODO: Why is this an illegal call?
//                    Pointer semaphore = library.sem_open(name, O_CREAT, 0666, 0);
//                    try {
//                        while (count-- > 0) {
//                            library.sem_post(semaphore);
//                        }
//                    } finally {
//                        library.sem_close(semaphore);
//                    }
                    try {
                        Method method = Class
                                .forName("com.ibm.tools.attach.target.IPC")
                                .getDeclaredMethod("notifyVm", String.class, String.class, int.class);
                        method.setAccessible(true);
                        method.invoke(null, directory.getAbsolutePath(), name, count);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                public void decrementSemaphore(File directory, String name, int count) {
                    // TODO: Why is this an illegal call?
//                    Pointer semaphore = library.sem_open(name, 0, 0666, 0);
//                    try {
//                        while (count-- > 0) {
//                            library.sem_wait(semaphore);
//                        }
//                    } finally {
//                        library.sem_close(semaphore);
//                    }
                    try {
                        Method method = Class
                                .forName("com.ibm.tools.attach.target.IPC")
                                .getDeclaredMethod("cancelNotify", String.class, String.class, int.class);
                        method.setAccessible(true);
                        method.invoke(null, directory.getAbsolutePath(), name, count);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                protected interface PosixLibrary extends Library {

                    int getpid() throws LastErrorException;

                    int getuid() throws LastErrorException;

                    int kill(long processId, int signal) throws LastErrorException;

                    void chmod(String name, int mode) throws LastErrorException;

                    Pointer sem_open(String name, int flags, int mode, int value) throws LastErrorException;

                    int sem_post(Pointer pointer) throws LastErrorException;

                    int sem_wait(Pointer pointer) throws LastErrorException;

                    int sem_close(Pointer pointer) throws LastErrorException;
                }
            }
        }
    }
}
