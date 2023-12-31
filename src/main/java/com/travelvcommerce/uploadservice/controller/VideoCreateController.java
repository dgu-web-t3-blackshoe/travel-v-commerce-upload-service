package com.travelvcommerce.uploadservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelvcommerce.uploadservice.dto.DenormalizedVideoDto;
import com.travelvcommerce.uploadservice.dto.TemporaryVideoDto;
import com.travelvcommerce.uploadservice.dto.VideoDto;
import com.travelvcommerce.uploadservice.service.*;
import com.travelvcommerce.uploadservice.dto.ResponseDto;
import com.travelvcommerce.uploadservice.service.KafkaVideoInfoProducerService;
import com.travelvcommerce.uploadservice.vo.S3Thumbnail;
import com.travelvcommerce.uploadservice.vo.S3Video;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/upload-service")
public class VideoCreateController {
    private final AwsS3Service awsS3Service;
    private final VideoCreateService videoCreateService;
    private final ObjectMapper objectMapper;
    private final ModelMapper modelMapper;
    private final KafkaVideoInfoProducerService kafkaVideoInfoProducerService;
    private final TemporaryVideoService temporaryVideoService;

    @PostMapping("/videos/{userId}")
    public ResponseEntity<ResponseDto> uploadVideo(@RequestHeader("Authorization") String id,
                                                   @PathVariable String userId,
                                                   @RequestPart(value = "video") MultipartFile video) {
        if (!id.equals(userId)) {
            ResponseDto responseDto = ResponseDto.buildResponseDto("Invalid id");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(responseDto);
        }

        String videoId = UUID.randomUUID().toString();
        String uploadedFilePath;
        String encodedFilePath;
        S3Video videoUrls;
        TemporaryVideoDto.TemporaryVideoResponseDto temporaryVideoResponseDto;

        try {
            uploadedFilePath = videoCreateService.uploadVideo(userId, videoId, video);
            encodedFilePath = videoCreateService.encodeVideo(userId, videoId, uploadedFilePath);
            videoUrls = awsS3Service.uploadEncodedVideo(userId, videoId, encodedFilePath);
            temporaryVideoResponseDto = temporaryVideoService.createTemporaryVideo(userId, videoId, videoUrls);
        } catch (NoSuchElementException e) {
            ResponseDto responseDto = ResponseDto.buildResponseDto(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(responseDto);
        } catch (RuntimeException e) {
            temporaryVideoService.deleteTemporaryVideo(userId, videoId);
            ResponseDto responseDto = ResponseDto.buildResponseDto(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseDto);
        }

        temporaryVideoService.checkAndDeleteExpiredVideo(videoId);

        ResponseDto responseDto = ResponseDto.buildResponseDto(objectMapper.convertValue(temporaryVideoResponseDto, Map.class));

        return ResponseEntity.status(HttpStatus.OK).body(responseDto);
    }

    @PostMapping("/videos/{userId}/{videoId}")
    public ResponseEntity<ResponseDto> createVideo(@RequestHeader("Authorization") String id,
                                                   @PathVariable String userId,
                                                   @PathVariable String videoId,
                                                   @RequestPart(value = "thumbnail") MultipartFile thumbnail,
                                                   @RequestPart(value = "requestUpload")
                                                   VideoDto.VideoUploadRequestDto videoUploadRequestDto) {
        if (!id.equals(userId)) {
            ResponseDto responseDto = ResponseDto.buildResponseDto("Invalid id");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(responseDto);
        }

        S3Video videoUrls;
        S3Thumbnail thumbnailUrls;
        DenormalizedVideoDto denormalizedVideoDto;

        try {
            videoUrls = temporaryVideoService.findTemporaryVideoUrls(userId, videoId);
            thumbnailUrls = awsS3Service.uploadThumbnail(userId, videoId, thumbnail);
            denormalizedVideoDto = videoCreateService.createVideo(userId, videoId, videoUploadRequestDto, videoUrls, thumbnailUrls);
        } catch (NoSuchElementException e) {
            ResponseDto responseDto = ResponseDto.buildResponseDto(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(responseDto);
        } catch (IllegalArgumentException e) {
            ResponseDto responseDto = ResponseDto.buildResponseDto(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(responseDto);
        } catch (RuntimeException e) {
            ResponseDto responseDto = ResponseDto.buildResponseDto(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseDto);
        }

        kafkaVideoInfoProducerService.createVideo(denormalizedVideoDto);
        temporaryVideoService.deleteTemporaryVideo(userId, videoId);

        VideoDto.VideoCreateResponseDto videoCreateResponseDto = modelMapper.map(denormalizedVideoDto, VideoDto.VideoCreateResponseDto.class);
        ResponseDto responseDto = ResponseDto.buildResponseDto(objectMapper.convertValue(videoCreateResponseDto, Map.class));

        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }
}
