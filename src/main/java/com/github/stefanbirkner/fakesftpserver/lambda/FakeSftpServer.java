package com.github.stefanbirkner.fakesftpserver.lambda;

import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

import static com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder.newLinux;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.*;
import static java.util.Collections.singletonList;

/**
 * {@code FakeSftpServer} runs an in-memory SFTP server while your tests are
 * running.
 * <p>It is used by wrapping your test code with the method
 * {@link #withSftpServer(ExceptionThrowingConsumer) withSftpServer}.
 * <pre>
 * public class TestClass {
 *   &#064;Test
 *   public void someTest() throws Exception {
 *     withSftpServer(server -&gt; {
 *       //test code
 *     });
 *   }
 * }
 * </pre>
 * <p>{@code withSftpServer} starts an SFTP server before executing the test
 * code and shuts down the server afterwards. The test code uses the provided
 * {@code FakeSftpServer} object to get information about the running server or
 * use additional features of {@code FakeSftpServer}.
 * <p>By default the SFTP server listens on an auto-allocated port. During the
 * test this port can be obtained by {@link #getPort() server.getPort()}. It
 * can be changed by calling {@link #setPort(int)}. The server is restarted
 * whenever this method is called.
 * <pre>
 * withSftpServer(server -&gt; {
 *   server.{@link #setPort(int) setPort}(1234);
 *   //test code
 * });
 * </pre>
 * <p>You can interact with the SFTP server by using the SFTP protocol with
 * password authentication. By default the server accepts every pair of
 * username and password, but you can restrict it to specific pairs.
 * <pre>
 * withSftpServer(server -&gt; {
 *   server.{@link #addUser(String, String) addUser}("username", "password");
 *   //test code
 * });
 * </pre>
 *
 * <h2>Testing code that reads files</h2>
 * <p>If you test code that reads files from an SFTP server then you need the
 * server to provide these files. The server object has a shortcut for putting
 * files to the server.
 * <pre>
 * &#064;Test
 * public void testTextFile() throws Exception {
 *   withSftpServer(server -&gt; {
 *     {@link #putFile(String, String, Charset) server.putFile}("/directory/file.txt", "content of file", UTF_8);
 *     //code that downloads the file
 *   });
 * }
 *
 * &#064;Test
 * public void testBinaryFile() throws Exception {
 *   withSftpServer(server -&gt; {
 *     byte[] content = createContent();
 *     {@link #putFile(String, byte[]) server.putFile}("/directory/file.bin", content);
 *     //code that downloads the file
 *   });
 * }
 * </pre>
 * <p>Test data that is provided as an input stream can be put directly from
 * that input stream. This is very handy if your test data is available as a
 * resource.
 * <pre>
 * &#064;Test
 * public void testFileFromInputStream() throws Exception {
 *   withSftpServer(server -&gt; {
 *     InputStream is = getClass().getResourceAsStream("data.bin");
 *     {@link #putFile(String, InputStream) server.putFile}("/directory/file.bin", is);
 *     //code that downloads the file
 *   });
 * }
 * </pre>
 * <p>If you need an empty directory then you can use the method
 * {@link #createDirectory(String)}.
 * <pre>
 * &#064;Test
 * public void testDirectory() throws Exception {
 *   withSftpServer(server -&gt; {
 *     {@link #createDirectory(String) server.createDirectory}("/a/directory");
 *     //code that reads from or writes to that directory
 *   });
 * }
 * </pre>
 * <p>You may create multiple directories at once with
 * {@link #createDirectories(String...)}.
 * <pre>
 * &#064;Test
 * public void testDirectories() throws Exception {
 *   withSftpServer(server -&gt; {
 *     server.{@link #createDirectories(String...) createDirectories}(
 *       "/a/directory",
 *       "/another/directory"
 *     );
 *     //code that reads from or writes to that directories
 *   });
 * }
 * </pre>
 *
 * <h2>Testing code that writes files</h2>
 * <p>If you test code that writes files to an SFTP server then you need to
 * verify the upload. The server object provides a shortcut for getting the
 * file's content from the server.
 * <pre>
 * &#064;Test
 * public void testTextFile() throws Exception {
 *   withSftpServer(server -&gt; {
 *     //code that uploads the file
 *     String fileContent = {@link #getFileContent(String, Charset) server.getFileContent}("/directory/file.txt", UTF_8);
 *     //verify file content
 *   });
 * }
 *
 * &#064;Test
 * public void testBinaryFile() throws Exception {
 *   withSftpServer(server -&gt; {
 *     //code that uploads the file
 *     byte[] fileContent = {@link #getFileContent(String) server.getFileContent}("/directory/file.bin");
 *     //verify file content
 *   });
 * }
 * </pre>
 *
 * <h2>Testing existence of files</h2>
 * <p>If you want to check whether a file was created or deleted then you can
 * verify that it exists or not.
 * <pre>
 * &#064;Test
 * public void testFile() throws Exception {
 *   withSftpServer(server -&gt; {
 *     //code that uploads or deletes the file
 *     boolean exists = {@link #existsFile(String) server.existsFile}("/directory/file.txt");
 *     //check value of exists variable
 *   });
 * }
 * </pre>
 * <p>The method returns {@code true} iff the file exists and it is not a directory.
 *
 * <h2>Delete all files</h2>
 * <p>If you want to reuse the SFTP server then you can delete all files and
 * directories on the SFTP server. (This is rarely necessary because the method
 * {@link #withSftpServer(ExceptionThrowingConsumer)} takes care that it starts
 * and ends with a clean SFTP server.)
 * <pre>server.{@link #deleteAllFilesAndDirectories() deleteAllFilesAndDirectories()};</pre>
 */
public class FakeSftpServer {

    /**
     * Starts an SFTP server, executes the test code and afterwards shuts
     * down the server.
     * @param testCode the code that is executed while the server is running
     * @throws Exception any exception thrown by {@code testCode}
     */
    public static void withSftpServer(
        ExceptionThrowingConsumer testCode
    ) throws Exception {
        try (
            FileSystem fileSystem = createFileSystem()
        ) {
            FakeSftpServer server = new FakeSftpServer(fileSystem);
            try (
                Closeable closeServer = server.start(0)
            ) {
                testCode.accept(server);
                server.withSftpServerFinished = true;
            }
        }
    }

    private static final SimpleFileVisitor<Path> DELETE_FILES_AND_DIRECTORIES
        = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attrs
            ) throws IOException {
                delete(file);
                return CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                Path dir,
                IOException exc
            ) throws IOException {
                if (dir.getParent() != null)
                    delete(dir);
                return super.postVisitDirectory(dir, exc);
            }
    };
    private static final Random RANDOM = new Random();
    protected final FileSystem fileSystem;
    private SshServer server;
    private boolean withSftpServerFinished = false;
    private final Map<String, String> usernamesAndPasswords = new HashMap<>();

    /**
     * {@code FakeSftpServer} should not be created manually (unless by
     * subclasses which know what they are doing). It is always provided
     * to an {@link ExceptionThrowingConsumer}
     * by {@link #withSftpServer(ExceptionThrowingConsumer)}.
     * @param fileSystem the file system that is used for storing the files
     */
    protected FakeSftpServer(
        FileSystem fileSystem
    ) {
        this.fileSystem = fileSystem;
    }

    /**
     * Returns the port of the SFTP server.
     *
     * @return the port of the SFTP server.
     */
    public int getPort() {
        verifyWithSftpServerIsNotFinished("call getPort()");
        return server.getPort();
    }

    /**
     * Set the port of the SFTP server. The SFTP server is restarted when you
     * call {@code setPort}.
     * @param port the port. Must be between 1 and 65535.
     * @throws IllegalArgumentException if the port is not between 1 and 65535.
     * @throws IllegalStateException if the server cannot be restarted.
     */
    public void setPort(
        int port
    ) {
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException(
                "Port cannot be set to " + port
                    + " because only ports between 1 and 65535 are valid."
            );
        verifyWithSftpServerIsNotFinished("set port");
        restartServer(port);
    }

    /**
     * Register a username with its password. After registering a username
     * it is only possible to connect to the server with one of the registered
     * username/password pairs.
     * <p>If {@code addUser} is called multiple times with the same username but
     * different passwords then the last password is effective.
     * @param username the username.
     * @param password the password for the specified username.
     * @return the server itself.
     */
    public FakeSftpServer addUser(
        String username,
        String password
    ) {
        usernamesAndPasswords.put(username, password);
        return this;
    }

    private void restartServer(
        int port
    ) {
        try {
            server.stop();
            start(port);
        } catch (IOException e) {
            throw new IllegalStateException(
                "The SFTP server cannot be restarted.",
                e
            );
        }
    }

    /**
     * Puts a text file to the SFTP folder. The file is available by the
     * specified path.
     * @param path the path to the file
     * @param content the file's content
     * @param encoding the encoding of the file
     * @throws IOException if the file cannot be written
     */
    public void putFile(
        String path,
        String content,
        Charset encoding
    ) throws IOException {
        byte[] contentAsBytes = content.getBytes(encoding);
        putFile(path, contentAsBytes);
    }

    /**
     * Puts a file to the SFTP folder. The file is available by the specified
     * path.
     * @param path the path to the file
     * @param content the file's content
     * @throws IOException if the file cannot be written
     */
    public void putFile(
        String path,
        byte[] content
    ) throws IOException {
        verifyWithSftpServerIsNotFinished("upload file");
        Path pathAsObject = fileSystem.getPath(path);
        ensureDirectoryOfPathExists(pathAsObject);
        write(pathAsObject, content);
    }

    /**
     * Puts a file to the SFTP folder. The file is available by the specified
     * path. The file's content is read from an {@code InputStream}.
     * @param path the path to the file
     * @param is an {@code InputStream} that provides the file's content
     * @throws IOException if the file cannot be written or the input stream
     * cannot be read
     */
    public void putFile(
        String path,
        InputStream is
    ) throws IOException {
        verifyWithSftpServerIsNotFinished("upload file");
        Path pathAsObject = fileSystem.getPath(path);
        ensureDirectoryOfPathExists(pathAsObject);
        copy(is, pathAsObject);
    }

    /**
     * Creates a directory on the SFTP server.
     * @param path the directory's path
     * @throws IOException if the directory cannot be created
     */
    public void createDirectory(
        String path
    ) throws IOException {
        verifyWithSftpServerIsNotFinished("create directory");
        Path pathAsObject = fileSystem.getPath(path);
        Files.createDirectories(pathAsObject);
    }

    /**
     * Create multiple directories on the SFTP server.
     * @param paths the directories' paths.
     * @throws IOException if at least one directory cannot be created.
     */
    public void createDirectories(
        String... paths
    ) throws IOException {
        for (String path: paths)
            createDirectory(path);
    }

    /**
     * Gets a text file's content from the SFTP server. The content is decoded
     * using the specified encoding.
     * @param path the path to the file
     * @param encoding the file's encoding
     * @return the content of the text file
     * @throws IOException if the file cannot be read
     */
    public String getFileContent(
        String path,
        Charset encoding
    ) throws IOException {
        byte[] content = getFileContent(path);
        return new String(content, encoding);
    }

    /**
     * Gets a file from the SFTP server.
     * @param path the path to the file
     * @return the content of the file
     * @throws IOException if the file cannot be read
     */
    public byte[] getFileContent(
        String path
    ) throws IOException {
        verifyWithSftpServerIsNotFinished("download file");
        Path pathAsObject = fileSystem.getPath(path);
        return readAllBytes(pathAsObject);
    }

    /**
     * Checks the existence of a file. Returns {@code true} iff the file exists
     * and it is not a directory.
     * @param path the path to the file
     * @return {@code true} iff the file exists and it is not a directory
     */
    public boolean existsFile(
        String path
    ) {
        verifyWithSftpServerIsNotFinished("check existence of file");
        Path pathAsObject = fileSystem.getPath(path);
        return exists(pathAsObject) && !isDirectory(pathAsObject);
    }

    /**
     * Deletes all files and directories.
     * @throws IOException if an I/O error is thrown while deleting the files
     * and directories
     */
    public void deleteAllFilesAndDirectories() throws IOException {
        for (Path directory: fileSystem.getRootDirectories())
            walkFileTree(directory, DELETE_FILES_AND_DIRECTORIES);
    }

    protected static FileSystem createFileSystem(
    ) throws IOException {
        return newLinux().build("FakeSftpServer-" + RANDOM.nextInt());
    }

    protected Closeable start(
        int port
    ) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator(this::authenticate);
        server.setSubsystemFactories(singletonList(new SftpSubsystemFactory()));
        /* When a channel is closed SshServer calls close() on the file system.
         * In order to use the file system for multiple channels/sessions we
         * have to use a file system wrapper whose close() does nothing.
         */
        server.setFileSystemFactory(new DoNotCloseFactory(fileSystem));
        server.start();
        this.server = server;
        return () -> this.server.close();
    }

    private boolean authenticate(
        String username,
        String password,
        ServerSession session
    ) {
        return usernamesAndPasswords.isEmpty()
            || Objects.equals(
                usernamesAndPasswords.get(username),
                password
            );
    }

    private void ensureDirectoryOfPathExists(
        Path path
    ) throws IOException {
        Path directory = path.getParent();
        if (directory != null && !directory.equals(path.getRoot()))
            Files.createDirectories(directory);
    }

    private void verifyWithSftpServerIsNotFinished(
        String task
    ) {
        if (withSftpServerFinished)
            throw new IllegalStateException(
                "Failed to " + task + " because withSftpServer is already finished."
            );
    }

    /**
     * Represents an operation that accepts a {@link FakeSftpServer} and returns
     * no result. This functional interfaces is expected to contain test code.
     */
    public interface ExceptionThrowingConsumer {

        /**
         * Performs an operation.
         *
         * @param server a running {@link FakeSftpServer}
         * @throws Exception any exception thrown by the operation
         */
        void accept(FakeSftpServer server) throws Exception;
    }

    private static class DoNotClose extends FileSystem {
        final FileSystem fileSystem;

        DoNotClose(
            FileSystem fileSystem
        ) {
            this.fileSystem = fileSystem;
        }

        @Override
        public FileSystemProvider provider() {
            return fileSystem.provider();
        }

        @Override
        public void close(
        ) throws IOException {
            //will not be closed
        }

        @Override
        public boolean isOpen() {
            return fileSystem.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return fileSystem.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return fileSystem.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            return fileSystem.getRootDirectories();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return fileSystem.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return fileSystem.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(
            String first,
            String... more
        ) {
            return fileSystem.getPath(first, more);
        }

        @Override
        public PathMatcher getPathMatcher(
            String syntaxAndPattern
        ) {
            return fileSystem.getPathMatcher(syntaxAndPattern);
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return fileSystem.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService(
        ) throws IOException {
            return fileSystem.newWatchService();
        }
    }

    private static class DoNotCloseFactory implements FileSystemFactory {
        final FileSystem fileSystem;

        DoNotCloseFactory(
            FileSystem fileSystem
        ) {
            this.fileSystem = fileSystem;
        }

        @Override
        public Path getUserHomeDir(
            SessionContext session
        ) {
            return null;
        }

        @Override
        public FileSystem createFileSystem(
            SessionContext session
        ) {
            return new DoNotClose(fileSystem);
        }
    }
}
