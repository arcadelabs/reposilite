/*
 * Copyright (c) 2020 Dzikoysk
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

package org.panda_lang.reposilite.repository;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.panda_lang.reposilite.Reposilite;
import org.panda_lang.reposilite.ReposiliteContext;
import org.panda_lang.reposilite.auth.Authenticator;
import org.panda_lang.reposilite.auth.Session;
import org.panda_lang.reposilite.error.ErrorDto;
import org.panda_lang.reposilite.error.FailureService;
import org.panda_lang.reposilite.error.ResponseUtils;
import org.panda_lang.reposilite.metadata.MetadataService;
import org.panda_lang.reposilite.utils.Result;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public final class DeployService {

    protected static final int RETRY_WRITE_TIME = 1000;

    private final boolean deployEnabled;
    private final Authenticator authenticator;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;
    private final FailureService failureService;
    private final ExecutorService executorService;

    public DeployService(
            boolean deployEnabled,
            Authenticator authenticator,
            RepositoryService repositoryService,
            MetadataService metadataService,
            FailureService failureService,
            ExecutorService executorService) {

        this.deployEnabled = deployEnabled;
        this.failureService = failureService;
        this.authenticator = authenticator;
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
        this.executorService = executorService;
    }

    public Result<CompletableFuture<Result<FileDetailsDto, ErrorDto>>, ErrorDto> deploy(ReposiliteContext context) {
        if (!deployEnabled) {
            return ResponseUtils.error(HttpStatus.SC_METHOD_NOT_ALLOWED, "Artifact deployment is disabled");
        }

        Result<Session, String> authResult = this.authenticator.authByUri(context.headers(), context.uri());

        if (authResult.containsError()) {
            return ResponseUtils.error(HttpStatus.SC_UNAUTHORIZED, authResult.getError());
        }

        if (!repositoryService.getDiskQuota().hasUsableSpace()) {
            return ResponseUtils.error(HttpStatus.SC_INSUFFICIENT_STORAGE, "Out of disk space");
        }

        File file = repositoryService.getFile(context.uri());
        FileDetailsDto fileDetails = FileDetailsDto.of(file);

        File metadataFile = new File(file.getParentFile(), "maven-metadata.xml");
        metadataService.clearMetadata(metadataFile);

        Reposilite.getLogger().info("DEPLOY " + authResult.getValue().getAlias() + " successfully deployed " + file + " from " + context.address());

        if (file.getName().contains("maven-metadata")) {
            return Result.ok(CompletableFuture.completedFuture(Result.ok(fileDetails)));
        }

        return Result.ok(writeFile(context, fileDetails, file));
    }

    protected CompletableFuture<Result<FileDetailsDto, ErrorDto>> writeFile(ReposiliteContext context, FileDetailsDto fileDetails, File file) {
        CompletableFuture<Result<FileDetailsDto, ErrorDto>> completableFuture = new CompletableFuture<>();

        executorService.submit(() -> {
            FileUtils.forceMkdirParent(file);

            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                FileLock lock = channel.lock();

                Files.copy(Objects.requireNonNull(context.input()), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                repositoryService.getDiskQuota().allocate(file.length());

                lock.release();
                return completableFuture.complete(Result.ok(fileDetails));
            } catch (OverlappingFileLockException overlappingFileLockException) {
                Thread.sleep(RETRY_WRITE_TIME);
                return completableFuture.complete(writeFile(context, fileDetails, file).get());
            } catch (Exception ioException) {
                failureService.throwException(context.uri(), ioException);
                return completableFuture.complete(ResponseUtils.error(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Failed to upload artifact"));
            }
        });

        return completableFuture;
    }

}