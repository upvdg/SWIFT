import qupath.lib.regions.RegionRequest
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.analysis.images.ContourTracing
import qupath.lib.common.GeneralTools
import org.bytedeco.opencv.global.opencv_imgcodecs as Imgcodecs
import qupath.lib.display.ImageDisplay
import javax.imageio.ImageIO
import qupath.opencv.tools.OpenCVTools
import java.util.function.BiFunction

// ============================ PARAMETERS ============================
def MODE                = "full"        // "detect", "segment", or "full"
double DOWNSAMPLE_DETECT = 2.0          // downsample used to export image for YOLO detection
double DOWNSAMPLE_SEGMENT = 1.0         // downsample used to export image(s) for SAM2 segmentation/tracking

boolean USE_SAM_TRACKING = true         // if true AND image has >1 timepoint: SAM2 tracks boxes across
                                         // the whole timelapse instead of segmenting a single frame
int REFERENCE_T           = 0           // timepoint used for YOLO detection / initial boxes

def WORK_DIR          = buildFilePath(PROJECT_BASE_DIR, "yolo_sam2")
def SCRIPTS_DIR       = buildFilePath(PROJECT_BASE_DIR, "scripts")
def MODELS_DIR        = buildFilePath(PROJECT_BASE_DIR, "models")

def PYTHON_EXE        = "/opt/conda/envs/yolo/bin/python"
def PY_SCRIPT         = SCRIPTS_DIR+"/organoid_pipeline.py"
def YOLO_WEIGHTS      = MODELS_DIR+"/bestuW.pt"

def SAM_WEIGHTS       = "sam2_b.pt"
double CONF           = 0.25

boolean LOAD_BOXES       = true    // load YOLO boxes as editable annotations (detect step)
boolean USE_EDITED_BOXES = true     // segment step: export current box annotations instead of trusting boxes.csv on disk
def BOX_CLASS_NAME       = "yolo_box"
def LOAD_LABELS_AS       = "annotations"  // "detections" or "annotations"
def SAM2_ORGANOID_CLASS  = "SAM2_organoid"
// ======================================================================

assert LOAD_LABELS_AS in ["detections", "annotations"]

mkdirs(WORK_DIR)

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def imageName = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
def nTimepoints = server.nTimepoints()
boolean doTrack = USE_SAM_TRACKING && nTimepoints > 1

def FRAMES_DIR      = buildFilePath(WORK_DIR, "${imageName}_frames_ds${DOWNSAMPLE_SEGMENT}")
def imgPathDetect   = buildFilePath(WORK_DIR, "${imageName}_ds${DOWNSAMPLE_DETECT}_t${REFERENCE_T}.jpg")
def boxesPath       = buildFilePath(WORK_DIR, "${imageName}_boxes_ds${DOWNSAMPLE_DETECT}.csv")
def labelPath       = buildFilePath(WORK_DIR, "${imageName}_labels_ds${DOWNSAMPLE_SEGMENT}.tif")
def labelsTrackDir  = buildFilePath(WORK_DIR, "${imageName}_tracked_labels_ds${DOWNSAMPLE_SEGMENT}")

def plane = ImagePlane.getPlane(0, REFERENCE_T)
def boxClass = PathClass.fromString(BOX_CLASS_NAME)

// ---------------- helpers ----------------

def exportImage = { double downsample, String path, int t = REFERENCE_T ->
    def request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight(), 0, t)
    def img = server.readRegion(request)          // original pixels (any type/#channels)
    def display = ImageDisplay.create(imageData)  // uses this image's current display settings
    def rgb = display.applyTransforms(img, null)  // converts to 8-bit RGB BufferedImage
    def format = path.toLowerCase().endsWith(".png") ? "png" : "jpg"
    ImageIO.write(rgb, format, new File(path))
    print "Image exported to: $path (downsample=$downsample, t=$t)"
}

def exportFrames = { double downsample, String dir ->
    mkdirs(dir)
    for (t in 0..<nTimepoints) {
        def framePath = buildFilePath(dir, String.format("frame_%04d.jpg", t))
        if (!new File(framePath).exists())
            exportImage(downsample, framePath, t)
    }
    print "Exported ${nTimepoints} frames to: $dir (downsample=$downsample)"
}

def loadBoxesAsAnnotations = { String csvPath, double downsample ->
    def lines = new File(csvPath).readLines()
    def toAdd = []
    lines.drop(1).each { line ->
        if (line.trim().isEmpty()) return
        def p = line.split(",")
        double x1 = p[0].toDouble() * downsample
        double y1 = p[1].toDouble() * downsample
        double x2 = p[2].toDouble() * downsample
        double y2 = p[3].toDouble() * downsample
        def roi = ROIs.createRectangleROI(x1, y1, x2 - x1, y2 - y1, plane)
        toAdd << PathObjects.createAnnotationObject(roi, boxClass)
    }
    removeObjects(getAnnotationObjects().findAll { it.getPathClass() == boxClass }, true)
    addObjects(toAdd)
    fireHierarchyUpdate()
    print "Loaded ${toAdd.size()} YOLO boxes as annotations (t=$REFERENCE_T)"
}

def exportAnnotationsAsBoxesCsv = { String csvPath, double downsample ->
    def annotations = getAnnotationObjects().findAll {
        it.getPathClass() == boxClass && it.getROI().getT() == REFERENCE_T
    }
    new File(csvPath).withPrintWriter { pw ->
        pw.println("x1,y1,x2,y2,score,class")
        annotations.each { a ->
            def r = a.getROI()
            double x1 = r.getBoundsX() / downsample
            double y1 = r.getBoundsY() / downsample
            double x2 = (r.getBoundsX() + r.getBoundsWidth()) / downsample
            double y2 = (r.getBoundsY() + r.getBoundsHeight()) / downsample
            pw.println("${x1},${y1},${x2},${y2},1.0,0")
        }
    }
    print "Exported ${annotations.size()} edited boxes to: $csvPath (downsample=$downsample)"
}

// Rescale a boxes CSV written at `fromDS` pixel units to `toDS` pixel units
def rescaleBoxesCsv = { String inPath, String outPath, double fromDS, double toDS ->
    def lines = new File(inPath).readLines()
    double scale = fromDS / toDS
    new File(outPath).withPrintWriter { pw ->
        pw.println(lines[0])
        lines.drop(1).each { line ->
            if (line.trim().isEmpty()) return
            def p = line.split(",")
            double x1 = p[0].toDouble() * scale
            double y1 = p[1].toDouble() * scale
            double x2 = p[2].toDouble() * scale
            double y2 = p[3].toDouble() * scale
            pw.println("${x1},${y1},${x2},${y2},${p[4]},${p[5]}")
        }
    }
    print "Rescaled boxes from ds=$fromDS to ds=$toDS -> $outPath"
}

def runPython = { String imagePath, List<String> extraArgs ->
    def command = [PYTHON_EXE, PY_SCRIPT, imagePath] + extraArgs
    print "Running: " + command.join(" ")
    def pb = new ProcessBuilder(command)
    pb.redirectErrorStream(true)
    def process = pb.start()
    process.getInputStream().eachLine { println it }
    def exitCode = process.waitFor()
    if (exitCode != 0)
        throw new RuntimeException("Python script failed with exit code $exitCode")
}

// Loads one label TIF into QuPath at timepoint t, preserving the pixel label as a Track ID
// (stable across timepoints => same organoid across the timelapse)
def loadLabelsFrame = { String tifPath, double downsample, int t ->
    def matLabels = Imgcodecs.imread(tifPath, Imgcodecs.IMREAD_UNCHANGED)
    def request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight(), 0, t)
    def simpleImg = OpenCVTools.matToSimpleImage(matLabels, 0)
    def pathClass = getPathClass(SAM2_ORGANOID_CLASS)

	BiFunction<ROI, Number, qupath.lib.objects.PathObject> creator = { roi, label ->
		def po = (LOAD_LABELS_AS == "annotations")
			? PathObjects.createAnnotationObject(roi, pathClass)
			: PathObjects.createDetectionObject(roi, pathClass)
		po.setName("Organoid_${label.intValue()}")
		po.getMeasurementList().put("Track ID", label.doubleValue())
		po.getMeasurementList().close()
		po
	}

    def objs = ContourTracing.createObjects(simpleImg, request, 1, 0, creator)
    addObjects(objs)
    return objs.size()
}

// ---------------- pipeline steps ----------------

def doDetect = {
    exportImage(DOWNSAMPLE_DETECT, imgPathDetect, REFERENCE_T)
    runPython(imgPathDetect, ["--mode", "detect", "--yolo-weights", YOLO_WEIGHTS, "--conf", CONF as String, "--boxes", boxesPath])
    if (LOAD_BOXES) loadBoxesAsAnnotations(boxesPath, DOWNSAMPLE_DETECT)
}

// Legacy behaviour: segment a single reference frame (no tracking)
def doSegmentStatic = {
    def segBoxesPath = buildFilePath(WORK_DIR, "${imageName}_boxes_ds${DOWNSAMPLE_SEGMENT}.csv")
    def imgPathSegment = buildFilePath(WORK_DIR, "${imageName}_ds${DOWNSAMPLE_SEGMENT}_t${REFERENCE_T}.jpg")

    if (USE_EDITED_BOXES) {
        exportAnnotationsAsBoxesCsv(segBoxesPath, DOWNSAMPLE_SEGMENT)
    } else {
        if (!new File(boxesPath).exists())
            throw new RuntimeException("Boxes CSV not found: $boxesPath (run detect first, or enable USE_EDITED_BOXES)")
        rescaleBoxesCsv(boxesPath, segBoxesPath, DOWNSAMPLE_DETECT, DOWNSAMPLE_SEGMENT)
    }

    if (!new File(imgPathSegment).exists()) exportImage(DOWNSAMPLE_SEGMENT, imgPathSegment, REFERENCE_T)

    runPython(imgPathSegment, ["--mode", "segment", "--sam-weights", SAM_WEIGHTS, "--boxes", segBoxesPath, "--out", labelPath])
    def n = loadLabelsFrame(labelPath, DOWNSAMPLE_SEGMENT, REFERENCE_T)
    fireHierarchyUpdate()
    print "Loaded ${n} organoid ${LOAD_LABELS_AS} from labels (t=$REFERENCE_T, downsample=$DOWNSAMPLE_SEGMENT)"
}

// SAM2 video tracking across the whole timelapse (boxes prompt the reference frame only)
def doSegmentTracking = {
    exportFrames(DOWNSAMPLE_SEGMENT, FRAMES_DIR)
    def refFramePath = buildFilePath(FRAMES_DIR, String.format("frame_%04d.jpg", REFERENCE_T))

    def segBoxesPath = buildFilePath(WORK_DIR, "${imageName}_boxes_ds${DOWNSAMPLE_SEGMENT}.csv")
    if (USE_EDITED_BOXES) {
        exportAnnotationsAsBoxesCsv(segBoxesPath, DOWNSAMPLE_SEGMENT)
    } else {
        if (!new File(boxesPath).exists())
            throw new RuntimeException("Boxes CSV not found: $boxesPath (run detect first, or enable USE_EDITED_BOXES)")
        rescaleBoxesCsv(boxesPath, segBoxesPath, DOWNSAMPLE_DETECT, DOWNSAMPLE_SEGMENT)
    }

    runPython(refFramePath, ["--mode", "segment", "--track", "--sam-weights", SAM_WEIGHTS, "--boxes", segBoxesPath, "--out", labelsTrackDir])

    def total = 0
    for (t in 0..<nTimepoints) {
        def labelPathT = buildFilePath(labelsTrackDir, String.format("labels_t%04d.tif", t))
        if (new File(labelPathT).exists())
            total += loadLabelsFrame(labelPathT, DOWNSAMPLE_SEGMENT, t)
        else
            print "WARNING: missing label file for t=$t ($labelPathT)"
    }
    fireHierarchyUpdate()
    print "Loaded ${total} tracked organoid ${LOAD_LABELS_AS} across ${nTimepoints} timepoints"
}

def doSegment = {
    if (doTrack) doSegmentTracking() else doSegmentStatic()
}

// ---------------- main ----------------

switch (MODE) {
    case "detect":
        removeAllObjects()
        doDetect()
        break
    case "segment":
        selectObjectsByClassification( SAM2_ORGANOID_CLASS );
        removeSelectedObjects()
        doSegment()
        break
    case "full":
        removeAllObjects()
        doDetect()
        doSegment()
        break
    default:
        throw new IllegalArgumentException("Unknown MODE: $MODE")
}

print "Done."