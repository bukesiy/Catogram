sed -i s/CATOGRAM_ALIAS/${KEY_ALIAS}/ gradle.properties
sed -i s/CATOGRAM_PASSWORD/${KEY_PASS}/ gradle.properties
sed -i s/CATOGRAM_STORE_PASSWORD/${STORE_PASS}/ gradle.properties
sed -i s/CATOGRAM_APP_ID/${API_KEY}/ TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java
sed -i s/CATOGRAM_API_HASH/${API_HASH}/ TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java
#-------------------#
./gradlew assembleArm64Release
#-------------------#
cd TMessagesProj/build/outputs/apk/arm64/release
curl -s -X POST "https://api.telegram.org/bot${TG_BOT_KEY}/sendMessage" -d chat_id="-1001293922100" \
  -d "disable_web_page_preview=true" \
  -d text="$(curl bashupload.com -T app.apk | cat)"
#-------------------# 
