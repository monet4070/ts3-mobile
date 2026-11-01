include(FetchContent)
include(${CMAKE_CURRENT_LIST_DIR}/opus-options.cmake)
file(READ ${CMAKE_CURRENT_LIST_DIR}/../../../../tools/opus-compat.lock.json OPUS_SOURCE_LOCK)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../../../../tools/opus-compat.lock.json)
set(TS3_OPUS_VERSION "1.6.1")
string(JSON OPUS_SOURCE_URL GET "${OPUS_SOURCE_LOCK}" "${TS3_OPUS_VERSION}" url)
string(JSON OPUS_SOURCE_MIRROR GET "${OPUS_SOURCE_LOCK}" "${TS3_OPUS_VERSION}" mirror)
string(JSON OPUS_SOURCE_SHA256 GET "${OPUS_SOURCE_LOCK}" "${TS3_OPUS_VERSION}" sha256)
set(TS3_OPUS_ARCHIVE "" CACHE FILEPATH "Optional local Opus archive, always checked against the pinned SHA-256")
if(TS3_OPUS_ARCHIVE)
    set(OPUS_SOURCE_LOCATIONS ${TS3_OPUS_ARCHIVE})
else()
    set(OPUS_SOURCE_LOCATIONS ${OPUS_SOURCE_URL} ${OPUS_SOURCE_MIRROR})
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(
    ts3_opus
    URL ${OPUS_SOURCE_LOCATIONS}
    URL_HASH SHA256=${OPUS_SOURCE_SHA256}
    TLS_VERIFY TRUE
    TIMEOUT 90
)
FetchContent_MakeAvailable(ts3_opus)
target_compile_options(opus PRIVATE -O3)
