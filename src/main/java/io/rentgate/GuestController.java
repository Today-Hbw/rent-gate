package io.rentgate;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class GuestController {
    private final DeviceService devices;
    private final String publicBase;
    public GuestController(DeviceService devices,@Value("${RENT_GATE_PUBLIC_BASE_URL:}") String publicBase) {
        this.devices=devices;
        if (!publicBase.isBlank()) {
            URI uri=URI.create(publicBase);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPath()!=null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
                throw new IllegalArgumentException("RENT_GATE_PUBLIC_BASE_URL must be an HTTP(S) origin without credentials or path");
            }
        }
        this.publicBase=publicBase.replaceAll("/+$","");
    }

    @GetMapping("/api/public/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token",token.getToken(),"header_name",token.getHeaderName());
    }
    @GetMapping("/api/public/devices/{token}") public DeviceService.GuestDevice guest(@PathVariable String token) { return devices.guest(token); }
    @PostMapping("/api/public/devices/{token}/trial") public DeviceService.GuestDevice trial(@PathVariable String token) { return devices.trial(token); }
    @GetMapping("/admin/devices/{id}/guest-link") public Map<String,String> guestLink(@PathVariable String id) { return Map.of("url",link(id)); }
    @GetMapping(value="/admin/devices/{id}/qr.png",produces=MediaType.IMAGE_PNG_VALUE)
    public byte[] qr(@PathVariable String id) throws Exception {
        String link=link(id);
        var matrix=new QRCodeWriter().encode(link,BarcodeFormat.QR_CODE,360,360);
        var output=new ByteArrayOutputStream(); MatrixToImageWriter.writeToStream(matrix,"PNG",output);
        return output.toByteArray();
    }
    private String link(String id) {
        String token=devices.device(id).publicToken();
        String base=publicBase.isBlank()?ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString():publicBase;
        return base+"/r/"+token;
    }
}
