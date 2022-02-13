package com.github.stefanbirkner.fakesftpserver.lambda;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystem;

/**
 * This extension of {@link FakeSftpServer} can be instantiated, configured and closed manually,
 * if doing everything within a single lambda expression is undesirable for your use case.
 * <p>For example, in BDD (behaviour-driven development) you want to test in a <i>given-when-then</i>
 * fashion, i.e. separate the stimulus to the system under test (when) from verifying results later
 * (then). Some test frameworks like Spock even require separate blocks, making the use of a single
 * lambda feel somewhat unnatural and clunky there.
 * <p>Because this class implements {@link AutoCloseable}, if you like you can instantiate it in a
 * <i>try with resources</i> structure.
 * <p>A parametrised Spock (Groovy) example test looks like this:
 * <pre>
 * class MySFTPServerTest extends Specification {
 *   &#064;AutoCleanup
 *   def server = new CloseableFakeSftpServer()
 *
 *   &#064;Unroll("user #user downloads file #file")
 *   def "users can download text files"() {
 *     given: "a preconfigured fake SFTP server"
 *     server.addUser(user, password)
 *     server.putFile(file, content, UTF_8)
 *
 *     and: "an SFTP client under test"
 *     def client = new SFTPFileDownloader("localhost", server.port, user, password)
 *
 *     expect:
 *     client.getFileContent(file) == content
 *
 *     where:
 *     user    | password | file                   | content
 *     "me"    | "xoxox"  | "/a/b/c/one.txt"       | "First line\nSecondLine\n"
 *     "you"   | "mypass" | "/etc/two.xml"         | "<root><foo>abc</foo></root>"
 *     "admin" | "secret" | "/home/admin/three.sh" | "#!/usr/bin/bash\n\nls -al\n"
 *   }
 * }
 * </pre>
 */
public class CloseableFakeSftpServer extends FakeSftpServer implements AutoCloseable {
    protected final Closeable closeServer;

    public CloseableFakeSftpServer() throws IOException {
        super(FakeSftpServer.createFileSystem());
        closeServer = start(0);
    }

    @Override
    public void close() throws Exception {
        fileSystem.close();
        closeServer.close();
    }
}
