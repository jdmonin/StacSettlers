# Delete existing stored games
PSQL="psql -Udeepsoc -d soc -c"
DATA=$($PSQL "select id from simulation_games;" | tail -n +3 | ghead -n -2)
for id in $DATA
do
    cmd="$PSQL 'drop table gameactions_$id, obsgamestates_$id, extgamestates_$id;'"
    cmd_del="$PSQL 'delete from simulation_games where id=$id;'"
    echo $cmd
    eval $cmd
    echo $cmd_del
    eval $cmd_del
done;
eval "$PSQL 'truncate table simulation_games;'"