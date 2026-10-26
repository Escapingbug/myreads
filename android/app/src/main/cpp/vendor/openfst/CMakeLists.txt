# Core OpenFst from the version used by WeText's Android runtime.
file(GLOB FST_CORE CONFIGURE_DEPENDS lib/*.cc)
add_library(official_fst STATIC ${FST_CORE})
target_include_directories(official_fst PUBLIC include)
target_compile_features(official_fst PUBLIC cxx_std_17)
target_link_libraries(official_fst PUBLIC ${CMAKE_DL_LIBS})
