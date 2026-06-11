// Script d'export YOLO pour une seule classe ("organoid") dans l'image active

def imageData = getCurrentImageData()
def server = imageData.getServer()
def imageName = GeneralTools.stripExtension(server.getMetadata().getName())
def imageWidth = server.getWidth()
def imageHeight = server.getHeight()

// Dossier de sortie
def exportDir = buildFilePath(PROJECT_BASE_DIR, "YOLO_labels")
mkdirs(exportDir)

def file = new File(exportDir, imageName + ".txt")
def writer = new BufferedWriter(new FileWriter(file))

// Parcourir chaque annotation
getAnnotationObjects().each { annotation ->
    def roi = annotation.getROI()
    def x = roi.getBoundsX()
    def y = roi.getBoundsY()
    def w = roi.getBoundsWidth()
    def h = roi.getBoundsHeight()

    def x_center = (x + w / 2.0) / imageWidth
    def y_center = (y + h / 2.0) / imageHeight
    def norm_w = w / imageWidth
    def norm_h = h / imageHeight

    def class_id = 0  // on suppose une seule classe : "organoid"

    writer.write(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f\n", class_id, x_center, y_center, norm_w, norm_h))
}

writer.close()

print "Export YOLO terminé pour l'image : " + imageName
