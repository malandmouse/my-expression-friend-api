package myexpressionfriend_api.common.controller;

import myexpressionfriend_api.common.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObjectStorageControllerTest {
    @Test
    void redirectsToFreshSignedUrlWithoutCachingIt() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.signedDownloadUrl("profiles/child/photo.jpg"))
                .thenReturn("https://storage.example/photo.jpg?signature=first",
                        "https://storage.example/photo.jpg?signature=second");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ObjectStorageController(storage)).build();
        for (String signature : new String[]{"first", "second"}) {
            mvc.perform(get("/uploads/profiles/child/photo.jpg"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(redirectedUrl("https://storage.example/photo.jpg?signature=" + signature));
        }
    }
}
