# BackgroundRemovers가 manifest meta-data의 클래스 이름으로 (Context) 생성자를 찾는다.
-keep class com.naury.framekit.segmentation.MlKitBackgroundRemover {
    public <init>(android.content.Context);
}
