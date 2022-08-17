package com.github.stefanbirkner.fakesftpserver.lambda;

import com.jcraft.jsch.*;
import org.apache.commons.io.IOUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.stefanbirkner.fakesftpserver.lambda.FakeSftpServer.withSftpServer;
import static com.github.stefanbirkner.fishbowl.Fishbowl.exceptionThrownBy;
import static com.github.stefanbirkner.fishbowl.Fishbowl.ignoreException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.toByteArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/* Wording according to the draft:
 * http://tools.ietf.org/html/draft-ietf-secsh-filexfer-13
 */
@RunWith(Enclosed.class)
public class FakeSftpServerTest {
    private static final byte[] DUMMY_CONTENT = new byte[]{1, 4, 2, 4, 2, 4};
    private static final int DUMMY_PORT = 46354;
    private static final InputStream DUMMY_STREAM = new ByteArrayInputStream(DUMMY_CONTENT);
    private static final JSch JSCH = new JSch();
    private static final int TIMEOUT = 200;

    public static class round_trip {
        @Test
        public void a_file_that_is_written_to_the_SFTP_server_can_be_read(
        ) throws Exception {
            withSftpServer(
                server -> {
                    Session session = connectToServer(server);
                    ChannelSftp channel = connectSftpChannel(session);
                    channel.put(
                        new ByteArrayInputStream(
                            "dummy content".getBytes(UTF_8)
                        ),
                        "dummy_file.txt"
                    );
                    InputStream file = channel.get("dummy_file.txt");
                    assertThat(IOUtils.toString(file, UTF_8))
                        .isEqualTo("dummy content");
                    channel.disconnect();
                    session.disconnect();
                }
            );
        }
    }

    public static class connection {

        @Test
        public void multiple_connections_to_the_server_are_possible(
        ) throws Exception {
            withSftpServer(
                server -> {
                    connectAndDisconnect(server);
                    connectAndDisconnect(server);
                }
            );
        }

        @Test
        public void a_client_can_connect_to_the_server_at_a_user_specified_port(
        ) throws Exception {
            withSftpServer(
                server -> {
                    server.setPort(8394);
                    connectToServerAtPort(8394);
                }
            );
        }
    }

    @RunWith(Enclosed.class)
    public static class authentication {
        public static class server_without_credentials {
            @Test
            public void the_server_accepts_connections_with_password(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        Session session = createSessionWithCredentials(
                            server,
                            "dummy user",
                            "dummy password"
                        );
                        session.connect(TIMEOUT);
                    }
                );
            }
        }

        public static class server_with_credentials {
            @Test
            public void the_server_accepts_connections_with_correct_password(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.addUser("dummy user", "dummy password");
                        Session session = createSessionWithCredentials(
                            server,
                            "dummy user",
                            "dummy password"
                        );
                        session.connect(TIMEOUT);
                    }
                );
            }

            @Test
            public void the_server_rejects_connections_with_wrong_password(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.addUser("dummy user", "correct password");
                        Session session = createSessionWithCredentials(
                            server,
                            "dummy user",
                            "wrong password"
                        );
                        assertAuthenticationFails(
                            () -> session.connect(TIMEOUT)
                        );
                    }
                );
            }

            @Test
            public void the_last_password_is_effective_if_addUser_is_called_multiple_times(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server
                            .addUser("dummy user", "first password")
                            .addUser("dummy user", "second password");
                        Session session = createSessionWithCredentials(
                            server,
                            "dummy user",
                            "second password"
                        );
                        session.connect(TIMEOUT);
                    }
                );
            }
        }

        private static Session createSessionWithCredentials(
            FakeSftpServer server,
            String username,
            String password
        ) throws JSchException {
            return FakeSftpServerTest.createSessionWithCredentials(
                username, password, server.getPort()
            );
        }

        private static void assertAuthenticationFails(
            ThrowingCallable connectToServer
        ) {
            assertThatThrownBy(connectToServer)
                .isInstanceOf(JSchException.class)
                .hasMessage("Auth fail");
        }
    }

    @RunWith(Enclosed.class)
    public static class file_upload {
        public static class a_text_file {
            @Test
            public void that_is_put_to_root_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.putFile(
                            "/dummy_file.txt",
                            "dummy content with umlaut ü",
                            UTF_8
                        );
                        byte[] file = downloadFile(server, "/dummy_file.txt");
                        assertThat(new String(file, UTF_8))
                            .isEqualTo("dummy content with umlaut ü");
                    }
                );
            }

            @Test
            public void that_is_put_to_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.putFile(
                            "/dummy_directory/dummy_file.txt",
                            "dummy content with umlaut ü",
                            UTF_8
                        );
                        byte[] file = downloadFile(
                            server,
                            "/dummy_directory/dummy_file.txt"
                        );
                        assertThat(new String(file, UTF_8))
                            .isEqualTo("dummy content with umlaut ü");
                    }
                );
            }

            @Test
            public void cannot_be_put_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    serverCapture::set
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().putFile(
                        "/dummy_file.txt", "dummy content", UTF_8
                    )
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to upload file because withSftpServer is"
                            + " already finished."
                    );
            }
        }

        public static class a_binary_file {
            @Test
            public void that_is_put_to_root_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.putFile("/dummy_file.bin", DUMMY_CONTENT);
                        byte[] file = downloadFile(server, "/dummy_file.bin");
                        assertThat(file).isEqualTo(DUMMY_CONTENT);
                    }
                );
            }

            @Test
            public void that_is_put_to_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.putFile(
                            "/dummy_directory/dummy_file.bin",
                            DUMMY_CONTENT
                        );
                        byte[] file = downloadFile(
                            server,
                            "/dummy_directory/dummy_file.bin"
                        );
                        assertThat(file).isEqualTo(DUMMY_CONTENT);
                    }
                );
            }

            @Test
            public void cannot_be_put_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    serverCapture::set
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().putFile(
                        "/dummy_file.bin",
                        DUMMY_CONTENT
                    )
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to upload file because withSftpServer is"
                            + " already finished."
                    );
            }
        }

        public static class a_file_from_a_stream {
            @Test
            public void that_is_put_to_root_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        InputStream is = new ByteArrayInputStream(DUMMY_CONTENT);
                        server.putFile("/dummy_file.bin", is);
                        byte[] file = downloadFile(server, "/dummy_file.bin");
                        assertThat(file).isEqualTo(DUMMY_CONTENT);
                    }
                );
            }

            @Test
            public void that_is_put_to_directory_by_the_server_object_can_be_read_from_server(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        InputStream is = new ByteArrayInputStream(DUMMY_CONTENT);
                        server.putFile("/dummy_directory/dummy_file.bin", is);
                        byte[] file = downloadFile(
                            server,
                            "/dummy_directory/dummy_file.bin"
                        );
                        assertThat(file).isEqualTo(DUMMY_CONTENT);
                    }
                );
            }

            @Test
            public void cannot_be_put_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    serverCapture::set
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().putFile(
                        "/dummy_file.bin",
                        DUMMY_STREAM
                    )
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to upload file because withSftpServer is"
                            + " already finished."
                    );
            }
        }
    }

    @RunWith(Enclosed.class)
    public static class directory_creation {

        public static class a_single_directory {
            @Test
            public void that_is_created_by_the_server_object_can_be_read_by_a_client(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.createDirectory("/a/directory");
                        assertEmptyDirectory(server, "/a/directory");
                    }
                );
            }

            @Test
            public void cannot_be_created_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    serverCapture::set
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().createDirectory("/a/directory")
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to create directory because withSftpServer is"
                            + " already finished."
                    );
            }
        }

        public static class multiple_directories {
            @Test
            public void that_are_created_by_the_server_object_can_be_read_by_a_client(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        server.createDirectories(
                            "/a/directory",
                            "/another/directory"
                        );
                        assertEmptyDirectory(server, "/a/directory");
                        assertEmptyDirectory(server, "/another/directory");
                    }
                );
            }

            @Test
            public void cannot_be_created_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    serverCapture::set
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().createDirectories(
                        "/a/directory",
                        "/another/directory"
                    )
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to create directory because withSftpServer is"
                            + " already finished."
                    );
            }
        }

        private static void assertEmptyDirectory(
            FakeSftpServer server,
            String directory
        ) throws JSchException, SftpException {
            Session session = connectToServer(server);
            ChannelSftp channel = connectSftpChannel(session);
            Vector entries = channel.ls(directory);
            assertThat(entries).hasSize(2); //these are the entries . and ..
            channel.disconnect();
            session.disconnect();
        }
    }

    @RunWith(Enclosed.class)
    public static class file_download {
        public static class a_text_file {
            @Test
            public void that_is_written_to_the_server_can_be_retrieved_by_the_server_object(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        uploadFile(
                            server,
                            "/dummy_directory/dummy_file.txt",
                            "dummy content with umlaut ü".getBytes(UTF_8)
                        );
                        String fileContent = server.getFileContent(
                            "/dummy_directory/dummy_file.txt",
                            UTF_8
                        );
                        assertThat(fileContent)
                            .isEqualTo("dummy content with umlaut ü");
                    }
                );
            }

            @Test
            public void cannot_be_retrieved_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    server -> {
                        uploadFile(
                            server,
                            "/dummy_directory/dummy_file.txt",
                            "dummy content".getBytes(UTF_8)
                        );
                        serverCapture.set(server);
                    }
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().getFileContent(
                        "/dummy_directory/dummy_file.txt"
                    )
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to download file because withSftpServer is"
                            + " already finished."
                    );
            }
        }

        public static class a_binary_file {
            @Test
            public void that_is_written_to_the_server_can_be_retrieved_by_the_server_object(
            ) throws Exception {
                withSftpServer(
                    server -> {
                        uploadFile(
                            server,
                            "/dummy_directory/dummy_file.bin",
                            DUMMY_CONTENT
                        );
                        byte[] fileContent = server.getFileContent(
                            "/dummy_directory/dummy_file.bin"
                        );
                        assertThat(fileContent).isEqualTo(DUMMY_CONTENT);
                    }
                );
            }

            @Test
            public void cannot_be_retrieved_outside_of_the_lambda(
            ) throws Exception {
                AtomicReference<FakeSftpServer> serverCapture
                    = new AtomicReference<>();
                withSftpServer(
                    server -> {
                        uploadFile(
                            server,
                            "/dummy_directory/dummy_file.bin",
                            DUMMY_CONTENT
                        );
                        serverCapture.set(server);
                    }
                );
                Throwable exception = exceptionThrownBy(
                    () -> serverCapture.get().getFileContent("/dummy_file.bin")
                );
                assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                        "Failed to download file because withSftpServer is"
                            + " already finished."
                    );
            }
        }
    }

    public static class file_existence_check {

        @Test
        public void exists_returns_true_for_a_file_that_exists_on_the_server(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    boolean exists = server.existsFile(
                        "/dummy_directory/dummy_file.bin"
                    );
                    assertThat(exists).isTrue();
                }
            );
        }

        @Test
        public void exists_returns_false_for_a_file_that_does_not_exists_on_the_server(
        ) throws Exception {
            withSftpServer(
                server -> {
                    boolean exists = server.existsFile(
                        "/dummy_directory/dummy_file.bin"
                    );
                    assertThat(exists).isFalse();
                }
            );
        }

        @Test
        public void exists_returns_false_for_a_directory_that_exists_on_the_server(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    boolean exists = server.existsFile("/dummy_directory");
                    assertThat(exists).isFalse();
                }
            );
        }

        @Test
        public void existence_of_a_file_cannot_be_checked_outside_of_the_lambda(
        ) throws Exception {
            AtomicReference<FakeSftpServer> serverCapture
                = new AtomicReference<>();
            withSftpServer(
                serverCapture::set
            );
            Throwable exception = exceptionThrownBy(
                () -> serverCapture.get().existsFile("/dummy_file.bin")
            );
            assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                    "Failed to check existence of file because withSftpServer"
                        + " is already finished."
                );
        }
    }

    public static class list_files_and_directories_check {
        @Test
        public void list_returns_all_existing_files_in_given_directory(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    uploadFile(
                        server,
                        "/dummy_directory/directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    Stream<Path> fileList = server.listFilesAndDirectories(
                        "/dummy_directory"
                    );
                    assertThat(fileList)
                        .hasSize(2)
                        .extracting(Path::toString)
                        .contains("/dummy_directory/dummy_file.bin", "/dummy_directory/directory");
                }
            );
        }

        @Test
        public void list_on_not_existing_directory_will_fail_with_exception(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    assertThatThrownBy(() -> server.listFilesAndDirectories(
                        "/unknown_directory"
                    )).isInstanceOf(NoSuchFileException.class);
                }
            );
        }

        @Test
        public void list_cannot_be_checked_outside_of_the_lambda(
        ) throws Exception {
            AtomicReference<FakeSftpServer> serverCapture
                = new AtomicReference<>();
            withSftpServer(
                serverCapture::set
            );
            Throwable exception = exceptionThrownBy(
                () -> serverCapture.get().listFilesAndDirectories("/dummy_directory")
            );
            assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                    "Failed to list files because withSftpServer"
                        + " is already finished."
                );
        }
    }

    public static class server_shutdown {
        @Test
        public void after_a_successful_test_SFTP_server_is_shutdown(
        ) throws Exception {
            AtomicInteger portCapture = new AtomicInteger();
            withSftpServer(
                server -> {
                    portCapture.set(server.getPort());
                }
            );
            assertConnectionToSftpServerNotPossible(portCapture.get());
        }

        @Test
        public void after_an_erroneous_test_SFTP_server_is_shutdown(
        ) throws Exception {
            AtomicInteger portCapture = new AtomicInteger();
            ignoreException(
                () -> withSftpServer(
                    server -> {
                        portCapture.set(server.getPort());
                        throw new RuntimeException();
                    }
                )
            );
            assertConnectionToSftpServerNotPossible(portCapture.get());
        }

        @Test
        public void after_a_test_first_SFTP_server_is_shutdown_when_port_was_changed_during_test(
        ) throws Exception {
            AtomicInteger portCapture = new AtomicInteger();
            withSftpServer(
                server -> {
                    portCapture.set(server.getPort());
                    server.setPort(DUMMY_PORT);
                }
            );
            assertConnectionToSftpServerNotPossible(portCapture.get());
        }

        @Test
        public void after_a_test_second_SFTP_server_is_shutdown_when_port_was_changed_during_test(
        ) throws Exception {
            withSftpServer(
                server -> {
                    server.setPort(DUMMY_PORT - 1);
                    server.setPort(DUMMY_PORT);
                }
            );
            assertConnectionToSftpServerNotPossible(DUMMY_PORT);
        }

        private void assertConnectionToSftpServerNotPossible(
            int port
        ) {
            Throwable throwable = catchThrowable(
                () -> connectToServerAtPort(port)
            );
            assertThat(throwable)
                .withFailMessage(
                    "SFTP server is still running on port %d.",
                    port
                )
                .hasCauseInstanceOf(ConnectException.class);
        }
    }

    public static class port_selection {
        @Test
        public void by_default_two_servers_run_at_different_ports(
        ) throws Exception {
            AtomicInteger portCaptureForFirstServer = new AtomicInteger();
            AtomicInteger portCaptureForSecondServer = new AtomicInteger();

            withSftpServer(
                firstServer -> {
                    portCaptureForFirstServer.set(firstServer.getPort());
                    withSftpServer(
                        secondServer -> portCaptureForSecondServer.set(
                            secondServer.getPort()
                        )
                    );
                }
            );

            assertThat(portCaptureForFirstServer)
                .doesNotHaveValue(portCaptureForSecondServer.get());
        }

        @Test
        public void the_port_can_be_changed_during_the_test(
        ) throws Exception {
            withSftpServer(
                server -> {
                    server.setPort(DUMMY_PORT);
                    connectToServerAtPort(DUMMY_PORT);
                }
            );
        }

        @Test
        public void it_is_not_possible_to_set_a_negative_port(
        ) throws Exception {
            assertThatThrownBy(
                () -> withSftpServer(
                    server -> server.setPort(-1)
                )
            )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                    "Port cannot be set to -1 because only ports between 1 and"
                        + " 65535 are valid."
                );
        }

        @Test
        public void it_is_not_possible_to_set_port_zero(
        ) throws Exception {
            assertThatThrownBy(
                () -> withSftpServer(
                    server -> server.setPort(0)
                )
            )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                    "Port cannot be set to 0 because only ports between 1 and"
                        + " 65535 are valid."
                );
        }

        @Test
        public void the_port_can_be_set_to_1024(
        ) throws Exception {
            //In a perfect world I would test to set port to 1 but the lowest
            //port that can be used by a non-root user is 1024
            withSftpServer(
                server -> server.setPort(1024)
            );
        }

        @Test
        public void the_server_can_be_run_at_port_65535(
        ) throws Exception {
            withSftpServer(
                server -> {
                    server.setPort(65535);
                    connectToServerAtPort(65535);
                }
            );
        }

        @Test
        public void it_is_not_possible_to_set_a_port_greater_than_65535(
        ) throws Exception {
            assertThatThrownBy(
                () -> withSftpServer(
                    server -> server.setPort(65536)
                )
            )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                    "Port cannot be set to 65536 because only ports between 1"
                        + " and 65535 are valid."
                );
        }

        @Test
        public void the_port_cannot_be_set_outside_of_the_lambda(
        ) throws Exception {
            AtomicReference<FakeSftpServer> serverCapture
                = new AtomicReference<>();
            withSftpServer(
                serverCapture::set
            );
            Throwable exception = exceptionThrownBy(
                () -> serverCapture.get().setPort(DUMMY_PORT)
            );
            assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                    "Failed to set port because withSftpServer is already"
                        + " finished."
                );
        }
    }

    public static class port_query {
        @Test
        public void can_be_read_during_the_test(
        ) throws Exception {
            AtomicInteger portCapture = new AtomicInteger();
            withSftpServer(
                server -> portCapture.set(server.getPort())
            );
            assertThat(portCapture).doesNotHaveValue(0);
        }

        @Test
        public void cannot_be_read_after_the_test(
        ) throws Exception {
            AtomicReference<FakeSftpServer> serverCapture
                = new AtomicReference<>();
            withSftpServer(
                serverCapture::set
            );
            assertPortCannotBeRead(serverCapture.get());
        }

        private void assertPortCannotBeRead(FakeSftpServer server) {
            assertThatThrownBy(server::getPort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                    "Failed to call getPort() because withSftpServer is already"
                        + " finished."
                );
        }
    }

    public static class cleanup {

        @Test
        public void deletes_file_in_root_directory(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    server.deleteAllFilesAndDirectories();
                    assertFileDoesNotExist(
                        server,
                        "/dummy_file.bin"
                    );
                }
            );
        }

        @Test
        public void deletes_file_in_directory(
        ) throws Exception {
            withSftpServer(
                server -> {
                    uploadFile(
                        server,
                        "/dummy_directory/dummy_file.bin",
                        DUMMY_CONTENT
                    );
                    server.deleteAllFilesAndDirectories();
                    assertFileDoesNotExist(
                        server,
                        "/dummy_directory/dummy_file.bin"
                    );
                }
            );
        }

        @Test
        public void deletes_directory(
        ) throws Exception {
            withSftpServer(
                server -> {
                    server.createDirectory("/dummy_directory");
                    server.deleteAllFilesAndDirectories();
                    assertDirectoryDoesNotExist(
                        server,
                        "/dummy_directory"
                    );
                }
            );
        }

        @Test
        public void works_on_an_empty_filesystem(
        ) throws Exception {
            withSftpServer(
                FakeSftpServer::deleteAllFilesAndDirectories
            );
        }

        private static void assertFileDoesNotExist(
            FakeSftpServer server,
            String path
        ) {
            boolean exists = server.existsFile(path);
            assertThat(exists).isFalse();
        }

        private static void assertDirectoryDoesNotExist(
            FakeSftpServer server,
            String directory
        ) throws JSchException {
            Session session = connectToServer(server);
            ChannelSftp channel = connectSftpChannel(session);
            try {
                assertThatThrownBy(() -> channel.ls(directory))
                    .isInstanceOf(SftpException.class)
                    .hasMessage("No such file or directory");
            } finally {
                channel.disconnect();
                session.disconnect();
            }
        }
    }

    private static Session connectToServer(
        FakeSftpServer server
    ) throws JSchException {
        return connectToServerAtPort(server.getPort());
    }

    private static Session connectToServerAtPort(
        int port
    ) throws JSchException {
        Session session = createSessionWithCredentials(
            "dummy user", "dummy password", port
        );
        session.connect(TIMEOUT);
        return session;
    }

    private static ChannelSftp connectSftpChannel(
        Session session
    ) throws JSchException {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();
        return channel;
    }

    private static void connectAndDisconnect(
        FakeSftpServer server
    ) throws JSchException {
        Session session = connectToServer(server);
        ChannelSftp channel = connectSftpChannel(session);
        channel.disconnect();
        session.disconnect();
    }

    private static Session createSessionWithCredentials(
        String username,
        String password,
        int port
    ) throws JSchException {
        Session session = JSCH.getSession(username, "127.0.0.1", port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setPassword(password);
        return session;
    }

    private static byte[] downloadFile(
        FakeSftpServer server,
        String path
    ) throws JSchException, SftpException, IOException {
        Session session = connectToServer(server);
        ChannelSftp channel = connectSftpChannel(session);
        try {
            InputStream is = channel.get(path);
            return toByteArray(is);
        } finally {
            channel.disconnect();
            session.disconnect();
        }
    }

    private static void uploadFile(
        FakeSftpServer server,
        String pathAsString,
        byte[] content
    ) throws JSchException, SftpException {
        Session session = connectToServer(server);
        ChannelSftp channel = connectSftpChannel(session);
        try {
            Path path = Paths.get(pathAsString);
            if (!path.getParent().equals(path.getRoot()))
                channel.mkdir(path.getParent().toString());
            channel.put(new ByteArrayInputStream(content), pathAsString);
        } finally {
            channel.disconnect();
            session.disconnect();
        }
    }
}
