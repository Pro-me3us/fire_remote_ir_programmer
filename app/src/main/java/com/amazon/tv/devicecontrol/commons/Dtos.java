package com.amazon.tv.devicecontrol.commons;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-compatible copies of the DTOs the Fire TV "devicecontrol" service deserializes in
 * IDeviceControlApi.pushToRemote(). Compatibility rules (Java serialization):
 *   - class name must match exactly (hence this package + nested-class layout),
 *   - serialVersionUID must match (values below were read from the service APK),
 *   - same-named fields must have the same type; fields missing here are left null/0 in the service.
 * Optional service-side fields we don't need (IrCodeSet.confidence, IrCodeSet.dateUpdated,
 * IrCode.descriptiveFunction) are intentionally omitted.
 */
public class Dtos {

    public static class IrCode implements Serializable {
        private static final long serialVersionUID = -3674958096272094252L;

        private String code1;
        private String code2;
        private Definitions.DeviceFunction deviceFunction;
        private boolean directInput;

        public IrCode() {}

        public IrCode(Definitions.DeviceFunction function, String code1, String code2, boolean directInput) {
            this.deviceFunction = function;
            this.code1 = code1;
            this.code2 = code2;
            this.directInput = directInput;
        }
    }

    public static class IrCodeSet implements Serializable {
        private static final long serialVersionUID = 2491539403193994513L;

        int blastCount;
        int dutyCycle;
        Long id;
        List<IrCode> irCodes = new ArrayList<IrCode>();
        String name;

        public IrCodeSet() {}

        public IrCodeSet(String name, long id, int blastCount, int dutyCycle, List<IrCode> codes) {
            this.name = name;
            this.id = id;
            this.blastCount = blastCount;
            this.dutyCycle = dutyCycle;
            this.irCodes = new ArrayList<IrCode>(codes);
        }
    }

    public static class RemoteButtonFunction implements Serializable {
        private static final long serialVersionUID = -1461179937713359787L;

        private Definitions.RemoteButton button;
        private Definitions.DeviceFunction function;

        public RemoteButtonFunction() {}

        public RemoteButtonFunction(Definitions.RemoteButton button, Definitions.DeviceFunction function) {
            this.button = button;
            this.function = function;
        }
    }
}
