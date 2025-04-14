package io.skoshchi.yaml;

public class LraProxyConfig {
   private LraProxy lraProxy;

   public LraProxyConfig() {
   }

   public LraProxy getLraProxy() {
      return lraProxy;
   }

   public void setLraProxy(LraProxy lraProxy) {
      this.lraProxy = lraProxy;
   }

   public String getStartLRAUrl() {
      return lraProxy.getUrl() + "/" + lraProxy.getServiceName() + "/" + lraProxy.getStart().getPath();
   }

   public String getCompleteLRAUrl() {
      return lraProxy.getUrl() + "/" + lraProxy.getServiceName() + "/" + lraProxy.getComplete().getPath();
   }
}
