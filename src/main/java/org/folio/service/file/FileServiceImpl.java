package org.folio.service.file;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.dataImport.util.OkapiConnectionParams;
import org.folio.rest.jaxrs.model.FileDefinition;
import org.folio.rest.jaxrs.model.StatusDto;
import org.folio.rest.jaxrs.model.UploadDefinition;
import org.folio.service.storage.FileStorageServiceBuilder;
import org.folio.service.upload.UploadDefinitionService;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class FileServiceImpl implements FileService {

  private static final Logger logger = LoggerFactory.getLogger(FileServiceImpl.class);

  private Vertx vertx;
  private UploadDefinitionService uploadDefinitionService;
  private String tenantId;

  public FileServiceImpl(UploadDefinitionService uploadDefinitionService) {
    this.uploadDefinitionService = uploadDefinitionService;
  }

  public FileServiceImpl(Vertx vertx, String tenantId, UploadDefinitionService uploadDefinitionService) {
    this.vertx = vertx;
    this.tenantId = tenantId;
    this.uploadDefinitionService = uploadDefinitionService;
  }

  @Override
  public Future<UploadDefinition> uploadFile(String fileId, String uploadDefinitionId, InputStream data, OkapiConnectionParams params) {
    return uploadDefinitionService.updateBlocking(uploadDefinitionId, uploadDefinition -> {
      Future<UploadDefinition> future = Future.future();
      Optional<FileDefinition> optionalFileDefinition = uploadDefinition.getFileDefinitions().stream().filter(fileFilter -> fileFilter.getId().equals(fileId))
        .findFirst();
      if (optionalFileDefinition.isPresent()) {
        FileDefinition fileDefinition = optionalFileDefinition.get();
        FileStorageServiceBuilder
          .build(vertx, tenantId, params)
          .map(service -> service.saveFile(data, fileDefinition, params)
            .setHandler(onFileSave -> {
              if (onFileSave.succeeded()) {
                uploadDefinition.setFileDefinitions(replaceFile(uploadDefinition.getFileDefinitions(),
                  onFileSave.result().withUploadedDate(new Date())));
                uploadDefinition.setStatus(uploadDefinition.getFileDefinitions().stream().allMatch(FileDefinition::getLoaded)
                  ? UploadDefinition.Status.LOADED
                  : UploadDefinition.Status.IN_PROGRESS);
                uploadDefinitionService.updateJobExecutionStatus(fileDefinition.getJobExecutionId(), new StatusDto().withStatus(StatusDto.Status.FILE_UPLOADED), params)
                  .setHandler(booleanAsyncResult -> {
                    if (booleanAsyncResult.succeeded()) {
                      future.complete(uploadDefinition);
                    } else {
                      String statusUpdateErrorMessage = "Error updating status for JobExecution with id " + fileDefinition.getJobExecutionId();
                      logger.error(statusUpdateErrorMessage);
                      future.fail(statusUpdateErrorMessage);
                    }
                  });
              } else {
                String fileSaveErrorMessage = "Error during file save";
                logger.error(fileSaveErrorMessage);
                future.fail(fileSaveErrorMessage);
              }
            }));
      } else {
        String errorMessage = "FileDefinition not found. FileDefinition ID: " + fileId;
        logger.error(errorMessage);
        future.fail(errorMessage);
      }
      return future;
    });
  }

  @Override
  public Future<Boolean> deleteFile(String id, String uploadDefinitionId, OkapiConnectionParams params) {
    return uploadDefinitionService.updateBlocking(uploadDefinitionId, uploadDefinition -> {
      Future<UploadDefinition> future = Future.future();
      List<FileDefinition> definitionList = uploadDefinition.getFileDefinitions();
      Optional<FileDefinition> optionalFileDefinition = definitionList
        .stream()
        .filter(f -> f.getId().equals(id)).findFirst();
      if (optionalFileDefinition.isPresent()) {
        FileDefinition fileDefinition = optionalFileDefinition.get();
        uploadDefinitionService.deleteFile(fileDefinition, params)
          .setHandler(deleteFileResult -> {
            if (deleteFileResult.succeeded()) {
              definitionList.remove(fileDefinition);
              uploadDefinition.setFileDefinitions(definitionList);
              future.complete(uploadDefinition);
              uploadDefinitionService.updateJobExecutionStatus(fileDefinition.getJobExecutionId(), new StatusDto().withStatus(StatusDto.Status.DISCARDED), params)
                .setHandler(updateStatusResult -> {
                  if (updateStatusResult.failed()) {
                    logger.error(String.format(
                      "Couldn't update JobExecution status with id %s to DISCARDED after file with id %s was deleted",
                      fileDefinition.getJobExecutionId(), id));
                  }
                });
            } else {
              future.fail(deleteFileResult.cause());
            }
          });
      } else {
        String errorMessage = String.format("FileDefinition with id %s was not fount", id);
        logger.error(errorMessage);
        future.fail(errorMessage);
      }
      return future;
    }).map(Objects::nonNull);
  }

  private List<FileDefinition> replaceFile(List<FileDefinition> list, FileDefinition fileDefinition) {
    FileDefinition fileToReplace = null;
    for (FileDefinition definition : list) {
      if (definition.getId().equals(fileDefinition.getId())) {
        fileToReplace = definition;
        break;
      }
    }
    if (fileToReplace != null) {
      list.remove(fileToReplace);
    }
    list.add(fileDefinition);
    return list;
  }
}
