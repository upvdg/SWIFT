double iouThreshold = 0.5

def GTs = getAnnotationObjects().findAll { it.getPathClass()?.toString() == 'GT' }
def Yolos = getAnnotationObjects().findAll { it.getPathClass()?.toString() == 'Yolo_detection' }

def matchedGTs = [] as Set
def matchedYolos = [] as Set

int TP = 0

for (yolo in Yolos) {
    def roiY = yolo.getROI()
    def bestIoU = 0.0
    def bestGT = null

    for (gt in GTs) {
        if (matchedGTs.contains(gt)) continue
        def roiG = gt.getROI()
        def intersection = roiY.getGeometry().intersection(roiG.getGeometry())
        def union = roiY.getGeometry().union(roiG.getGeometry())
        def iou = intersection.getArea() / union.getArea()

        if (iou > bestIoU) {
            bestIoU = iou
            bestGT = gt
        }
    }

    if (bestIoU >= iouThreshold) {
        TP++
        matchedGTs.add(bestGT)
        matchedYolos.add(yolo)
    }
}

int FP = Yolos.size() - matchedYolos.size()
int FN = GTs.size() - matchedGTs.size()

double precision = TP / (TP + FP + 1e-6)
double recall = TP / (TP + FN + 1e-6)
double f1 = 2 * precision * recall / (precision + recall + 1e-6)

// Approximation de lAP par la pr�cision au rappel calcul�
double mAP = precision  // approx mAP@0.5

print "TP: $TP, FP: $FP, FN: $FN\n"
print "Precision: ${String.format('%.3f', precision)}\n"
print "Recall: ${String.format('%.3f', recall)}\n"
print "F1-score: ${String.format('%.3f', f1)}\n"
print "Approx. mAP@0.5: ${String.format('%.3f', mAP)}\n"
