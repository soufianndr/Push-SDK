//
//  H264HwEncoderImpl.m
//  h264v1
//
//  Created by Ganvir, Manish on 3/31/15.
//  Copyright (c) 2015 Ganvir, Manish. All rights reserved.
//

#import "H264HwEncoderImpl.h"
#define YUV_FRAME_SIZE 2000
#define FRAME_WIDTH
#define NUMBEROFRAMES 300
#define DURATION 12

@import VideoToolbox;
@import AVFoundation;

@interface H264HwEncoderImpl ()

@property(nonatomic,strong)NSMutableArray* videoRawArray;
@property(nonatomic,strong)NSMutableArray* videoEncodedArray;
@property(nonatomic,assign)long long smapleIndex;

@end

@implementation H264HwEncoderImpl
{
    NSString * yuvFile;
    VTCompressionSessionRef EncodingSession;
    dispatch_queue_t aQueue;
    CMFormatDescriptionRef  format;
    CMSampleTimingInfo * timingInfo;
    BOOL initialized;
    int  frameCount;
    NSData *sps;
    NSData *pps;
}
@synthesize error;

- (void) initWithConfiguration
{
//    NSFileManager *fileManager = [NSFileManager defaultManager];
//    NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
//    NSString *documentsDirectory = [paths objectAtIndex:0];
    
    /*yuvFile = [documentsDirectory stringByAppendingPathComponent:@"test.i420"];
    
    if ([fileManager fileExistsAtPath:yuvFile] == NO) {
        NSLog(@"H264: File does not exist");
        return;
    }*/
    
    EncodingSession = nil;
    initialized = true;
    aQueue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0);
    frameCount = 0;
    sps = NULL;
    pps = NULL;
    
}

void didCompressH264(void *outputCallbackRefCon, void *sourceFrameRefCon, OSStatus status, VTEncodeInfoFlags infoFlags,
CMSampleBufferRef sampleBuffer )
{
//    NSLog(@"didCompressH264 called with status %d infoFlags %d", (int)status, (int)infoFlags);
    if (status != 0) return;
    
    if (!CMSampleBufferDataIsReady(sampleBuffer))
    {
//        NSLog(@"didCompressH264 data is not ready ");
        return;
    }
   H264HwEncoderImpl* encoder = (__bridge H264HwEncoderImpl*)outputCallbackRefCon;
   
   // Check if we have got a key frame first
    bool keyframe = !CFDictionaryContainsKey( (CFArrayGetValueAtIndex(CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, true), 0)), kCMSampleAttachmentKey_NotSync);
    
   if (keyframe)
   {
        CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sampleBuffer);
       // CFDictionaryRef extensionDict = CMFormatDescriptionGetExtensions(format);
       // Get the extensions
       // From the extensions get the dictionary with key "SampleDescriptionExtensionAtoms"
       // From the dict, get the value for the key "avcC"
       
        size_t sparameterSetSize, sparameterSetCount;
        const uint8_t *sparameterSet;
        OSStatus statusCode = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, &sparameterSet, &sparameterSetSize, &sparameterSetCount, 0 );
        if (statusCode == noErr)
        {
            // Found sps and now check for pps
            size_t pparameterSetSize, pparameterSetCount;
            const uint8_t *pparameterSet;
            OSStatus statusCode = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 1, &pparameterSet, &pparameterSetSize, &pparameterSetCount, 0 );
            if (statusCode == noErr)
            {
                // Found pps
                encoder->sps = [NSData dataWithBytes:sparameterSet length:sparameterSetSize];
                encoder->pps = [NSData dataWithBytes:pparameterSet length:pparameterSetSize];
                if (encoder->_delegate)
                {
                    [encoder->_delegate gotSpsPps:encoder->sps pps:encoder->pps];
                }
            }
        }
    }
    CMTime presentationTimeStamp = CMSampleBufferGetOutputPresentationTimeStamp(sampleBuffer);
    long long  timeStamp = (1000*1000*presentationTimeStamp.value) / presentationTimeStamp.timescale;
    
    double time = CMTimeGetSeconds(CMSampleBufferGetOutputPresentationTimeStamp(sampleBuffer)) * 1000;
    
    
  //  NSLog(@"CMTime .. presentationTime:%lld timescale:%d time :%f",presentationTimeStamp.value,presentationTimeStamp.timescale,time);
    
    CMBlockBufferRef dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer);
    size_t length, totalLength;
    char *dataPointer;
    OSStatus statusCodeRet = CMBlockBufferGetDataPointer(dataBuffer, 0, &length, &totalLength, &dataPointer);
    if (statusCodeRet == noErr) {
        
        size_t bufferOffset = 0;
        static const int AVCCHeaderLength = 4;
        while (bufferOffset < totalLength - AVCCHeaderLength) {
            
            // Read the NAL unit length
            uint32_t NALUnitLength = 0;
            memcpy(&NALUnitLength, dataPointer + bufferOffset, AVCCHeaderLength);
            
            // Convert the length value from Big-endian to Little-endian
            NALUnitLength = CFSwapInt32BigToHost(NALUnitLength);
            
            NSData* data = [[NSData alloc] initWithBytes:(dataPointer + bufferOffset + AVCCHeaderLength) length:NALUnitLength];
            [encoder->_delegate gotEncodedData:data isKeyFrame:keyframe timeDuration:timeStamp];
            //Move to the next NAL unit in the block buffer
            bufferOffset += AVCCHeaderLength + NALUnitLength;
        }
        
    }
    
}

- (void)start:(int)width  height:(int)height
{
    int frameSize = (width * height * 1.5);

    if (!initialized)
    {
//        NSLog(@"H264: Not initialized");
        error = @"H264: Not initialized";
        return;
    }
    dispatch_sync(aQueue, ^{
        
        // For testing out the logic, lets read from a file and then send it to encoder to create h264 stream
        
        // Create the compression session
        OSStatus status = VTCompressionSessionCreate(NULL, width, height, kCMVideoCodecType_H264, NULL, NULL, NULL, didCompressH264, (__bridge void *)(self),  &EncodingSession);
//        NSLog(@"H264: VTCompressionSessionCreate %d", (int)status);
        
        if (status != 0)
        {
//            NSLog(@"H264: Unable to create a H264 session");
            error = @"H264: Unable to create a H264 session";

            return ;

        }
        
        // Set the properties
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFTypeRef _Nonnull)([NSNumber numberWithInt:60]));

        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel);

        // Tell the encoder to start encoding
        VTCompressionSessionPrepareToEncodeFrames(EncodingSession);
        
        // Start reading from the file and copy it to the buffer
        
        // Open the file using POSIX as this is anyway a test application
        int fd = open([yuvFile UTF8String], O_RDONLY);
        if (fd == -1)
        {
//            NSLog(@"H264: Unable to open the file");
            error = @"H264: Unable to open the file";

            return ;
        }
        
        NSMutableData* theData = [[NSMutableData alloc] initWithLength:frameSize] ;
        NSUInteger actualBytes = frameSize;
        while (actualBytes > 0)
        {
             void* buffer = [theData mutableBytes];
             NSUInteger bufferSize = [theData length];
            
             actualBytes = read(fd, buffer, bufferSize);
             if (actualBytes < frameSize)
                  [theData setLength:actualBytes];
            
            frameCount++;
            // Create a CM Block buffer out of this data
            CMBlockBufferRef BlockBuffer = NULL;
            OSStatus status = CMBlockBufferCreateWithMemoryBlock(NULL, buffer, actualBytes,kCFAllocatorNull, NULL, 0, actualBytes, kCMBlockBufferAlwaysCopyDataFlag, &BlockBuffer);
            
            // Check for error
            if (status != noErr)
            {
//                NSLog(@"H264: CMBlockBufferCreateWithMemoryBlock failed with %d", (int)status);
                error = @"H264: CMBlockBufferCreateWithMemoryBlock failed ";

                return ;
            }
            
            // Create a CM Sample Buffer
            CMSampleBufferRef sampleBuffer = NULL;
            CMFormatDescriptionRef formatDescription;
            CMFormatDescriptionCreate ( kCFAllocatorDefault, // Allocator
                                       kCMMediaType_Video,
                                       'I420',
                                       NULL,
                                       &formatDescription );
            CMSampleTimingInfo sampleTimingInfo = {CMTimeMake(1, 300)};
            
            OSStatus statusCode = CMSampleBufferCreate(kCFAllocatorDefault, BlockBuffer, YES, NULL, NULL, formatDescription, 1, 1, &sampleTimingInfo, 0, NULL, &sampleBuffer);
            
            // Check for error
            if (statusCode != noErr) {
//                NSLog(@"H264: CMSampleBufferCreate failed with %d", (int)statusCode);
                error = @"H264: CMSampleBufferCreate failed ";

                return;
            }
            CFRelease(BlockBuffer);
            BlockBuffer = NULL;
            
            // Get the CV Image buffer
            CVImageBufferRef imageBuffer = (CVImageBufferRef)CMSampleBufferGetImageBuffer(sampleBuffer);
            
            // Create properties
            CMTime presentationTimeStamp = CMTimeMake(frameCount, 300);
            //CMTime duration = CMTimeMake(1, DURATION);
            VTEncodeInfoFlags flags;
            
            // Pass it to the encoder
            statusCode = VTCompressionSessionEncodeFrame(EncodingSession,
                                            imageBuffer,
                                            presentationTimeStamp,
                                            kCMTimeInvalid,
                                            NULL, NULL, &flags);
            // Check for error
            if (statusCode != noErr) {
//                NSLog(@"H264: VTCompressionSessionEncodeFrame failed with %d", (int)statusCode);
                error = @"H264: VTCompressionSessionEncodeFrame failed ";
                
                // End the session
                VTCompressionSessionInvalidate(EncodingSession);
                CFRelease(EncodingSession);
                EncodingSession = NULL;
                error = NULL;
                return;
            }
//            NSLog(@"H264: VTCompressionSessionEncodeFrame Success");
            
        }
        
        // Mark the completion
        VTCompressionSessionCompleteFrames(EncodingSession, kCMTimeInvalid);
        
        // End the session
        VTCompressionSessionInvalidate(EncodingSession);
        CFRelease(EncodingSession);
        EncodingSession = NULL;
        error = NULL;

        close(fd);
    });
    
    
}
- (void)initEncode:(int)width  height:(int)height
{
    
    self.smapleIndex = 0;
    dispatch_sync(aQueue, ^{
        
        // For testing out the logic, lets read from a file and then send it to encoder to create h264 stream
        
        // Create the compression session
        OSStatus status = VTCompressionSessionCreate(NULL, width, height, kCMVideoCodecType_H264, NULL, NULL, NULL, didCompressH264, (__bridge void *)(self),  &EncodingSession);
//        NSLog(@"H264: VTCompressionSessionCreate %d", (int)status);
        
        if (status != 0)
        {
//            NSLog(@"H264: Unable to create a H264 session");
            error = @"H264: Unable to create a H264 session";
            
            return ;
            
        }
        
        int fixedBitrate = 1024 * 1024;
        NSNumber *values[] = {@(65536), @1};
        NSNumber* numberObj;
        
        
        
//        [self.definitionSWitch insertSegmentWithTitle:@"512" atIndex:0 animated:NO];
//        [self.definitionSWitch insertSegmentWithTitle:@"768" atIndex:1 animated:NO];
//        [self.definitionSWitch insertSegmentWithTitle:@"1M" atIndex:2 animated:NO];
//        [self.definitionSWitch insertSegmentWithTitle:@"1.5M" atIndex:3 animated:NO];
//        [self.definitionSWitch insertSegmentWithTitle:@"2M" atIndex:4 animated:NO];
        
        if (self.bitRate) {
            if ([self.bitRate isEqualToString:@"512"]) {
                fixedBitrate = 512*1024;
                numberObj = [NSNumber numberWithLongLong:65536];
            }else if ([self.bitRate isEqualToString:@"768"]){
                fixedBitrate = 768*1024;
                numberObj = [NSNumber numberWithLongLong:98304];
                
            }else if ([self.bitRate isEqualToString:@"1M"]){
                fixedBitrate = 1024*1024;
                numberObj = [NSNumber numberWithLongLong:131072];
                
            }else if ([self.bitRate isEqualToString:@"1.5M"]){
                
                fixedBitrate = 1280*1024;
                numberObj = [NSNumber numberWithLongLong:196608];
                
            }else if ([self.bitRate isEqualToString:@"2M"]){
                
                fixedBitrate = 2048*1024;
                numberObj = [NSNumber numberWithLongLong:262144];
                
            }else{
            }
    
        }
        long long numberFloat = [numberObj floatValue]/8;
        
        values[0] = numberObj;//[NSNumber numberWithLongLong:numberFloat];
        
        CFNumberRef bitrateNum = CFNumberCreate(NULL, kCFNumberSInt32Type, &fixedBitrate);
        int ret_val = VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_AverageBitRate, bitrateNum);
        if (ret_val != noErr) {
            NSLog(@"kVTCompressionPropertyKey_AverageBitRate failed %d", ret_val);
        }else
            NSLog(@"kVTCompressionPropertyKey_AverageBitRate succeed !!!");
      
        
        CFArrayRef array = CFArrayCreate(kCFAllocatorDefault, (void *)values, (CFIndex)2, NULL);
        ret_val = VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_DataRateLimits, array);
        
        if (ret_val != noErr) {
            NSLog(@"kVTCompressionPropertyKey_DataRateLimits failed %d", ret_val);
        } else
            NSLog(@"kVTCompressionPropertyKey_DataRateLimits succeed!!! ");
        
        // Set the properties
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Main_AutoLevel);
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFTypeRef _Nonnull)([NSNumber numberWithInt:25]));
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFTypeRef _Nonnull)([NSNumber numberWithInt:50]));
        VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, (__bridge CFTypeRef _Nonnull)([NSNumber numberWithInt:2]));
        // Tell the encoder to start encoding
        VTCompressionSessionPrepareToEncodeFrames(EncodingSession);
    });
}



- (void)encode:(CMSampleBufferRef )sampleBuffer
{
     dispatch_sync(aQueue, ^{
         
         ////force keyframe
         
         CFMutableDictionaryRef frameProps = NULL;
         frameProps = CFDictionaryCreateMutable(kCFAllocatorDefault, 1,&kCFTypeDictionaryKeyCallBacks,                                                            &kCFTypeDictionaryValueCallBacks);
         
         CFDictionaryAddValue(frameProps, kVTEncodeFrameOptionKey_ForceKeyFrame, kCFBooleanTrue);
        
         
         
         ////force keyframe end
        
          frameCount++;
         // Get the CV Image buffer
         CVImageBufferRef imageBuffer = (CVImageBufferRef)CMSampleBufferGetImageBuffer(sampleBuffer);
         
            // Create properties
            CMTime presentationTimeStamp = CMTimeMake(frameCount, 1000);
            //CMTime duration = CMTimeMake(1, DURATION);
            VTEncodeInfoFlags flags;
            // Pass it to the encoder
            OSStatus statusCode;
         
         
         if (self.smapleIndex%50==0) {
             statusCode = VTCompressionSessionEncodeFrame(EncodingSession,
                                                          imageBuffer,
                                                          presentationTimeStamp,
                                                          kCMTimeInvalid,
                                                          frameProps, NULL, &flags);
             NSLog(@"keyyyyy frame");
         }else{
             statusCode = VTCompressionSessionEncodeFrame(EncodingSession,
                                                          imageBuffer,
                                                          presentationTimeStamp,
                                                          kCMTimeInvalid,
                                                          NULL, NULL, &flags);
         }
         self.smapleIndex++;
         
         CFRelease(frameProps);
            // Check for error
            if (statusCode != noErr) {
//                NSLog(@"H264: VTCompressionSessionEncodeFrame failed with %d", (int)statusCode);
                error = @"H264: VTCompressionSessionEncodeFrame failed ";
                
                // End the session
                VTCompressionSessionInvalidate(EncodingSession);
                CFRelease(EncodingSession);
                EncodingSession = NULL;
                error = NULL;
                return;
            }
//            NSLog(@"H264: VTCompressionSessionEncodeFrame Success");
       });
    
    
}

- (void)changeResolution:(int)width  height:(int)height
{
}

- (void)End
{
    // Mark the completion
    VTCompressionSessionCompleteFrames(EncodingSession, kCMTimeInvalid);
    
    // End the session
    VTCompressionSessionInvalidate(EncodingSession);
    CFRelease(EncodingSession);
    EncodingSession = NULL;
    error = NULL;
    self.smapleIndex = 0;

}

-(IAMediaDataModel*)audioEncodedDataModel{
    return nil;
}
-(void)removeEncodedData:(IAMediaDataModel*)encodedModel{
}

@end
