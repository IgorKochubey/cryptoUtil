# cryptoUtil
Утилита которая делает:
1) авторизацию
2) создание токена и его вывод
3) создание документа в ЧЗ исходя из типа
4) вывод статуса документа ЧЗ
5) работает как под windows так и под debian

## сборка jar
mvn clean-package

## настройка
в папке рядом с jar должны быть файлы:
1) input.txt (внутри него json)
2) [application.properties](application.properties)

## [application.properties](application.properties):
1) certSerial=uid ключа
2) csptestPath=путь к крипто про:
- /opt/cprocsp/bin/amd64/csptest для debian 
- c:\\\\Program Files\\\Crypto Pro\\\CSP\\\csptest.exe для windows

## типы команд:
1) LK_RECEIPT = вывод из оборота
2) SETS_AGGREGATION = формирование набора

## запуск
i.kochubei@tst-jav-crp301:~/crypto_test/java$ sudo -u tomcat java -jar crypto.jar input.txt 7721788616 LK_RECEIPT

## где какие инн:
1) ООО "Ин-Ритейл" = 7813450665
2) ООО "СМАРТ ФЭМИЛИ" = 7721788616 нет гс1рус

нужно для авторизации и указания в json при использовании одного и того же ключа для разных фирм