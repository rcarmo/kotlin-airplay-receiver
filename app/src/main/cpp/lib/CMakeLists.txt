cmake_minimum_required(VERSION 3.4.1)
include_directories(crypto curve25519 ed25519 playfair plist/plist
        alac
        fdk-aac/libAACdec/include
        fdk-aac/libFDK/include
        fdk-aac/libMpegTPDec/include
        fdk-aac/libPCMutils/include
        fdk-aac/libSBRdec/include
        fdk-aac/libSYS/include
        #fdk-aac/libArithCoding/include
        #fdk-aac/libDRCdec/include
        #fdk-aac/libSACdec/include
        )


aux_source_directory(. play_src)
set(DIR_SRCS ${play_src})

add_library(alac STATIC
        alac/ALACDecoder.cpp
        alac/ALACBitUtilities.c
        alac/EndianPortable.c
        alac/ag_dec.c
        alac/dp_dec.c
        alac/matrix_dec.c
        alac/alac_decoder_wrapper.cpp
        )
target_include_directories(alac PUBLIC alac)

add_library( play-lib
        SHARED
        ${DIR_SRCS}
        )
set_target_properties(play-lib PROPERTIES LINKER_LANGUAGE CXX)
find_library( log-lib
        log )
target_link_libraries( play-lib
        alac
        crypto
        curve25519
        ed25519
        playfair
        plist
        fdk-aac
        ${log-lib})
